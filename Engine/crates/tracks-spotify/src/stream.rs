//! Decoded audio, pulled by the host: no output device here. iOS feeds it to an
//! `AVAudioSourceNode`, Android to Media3. Reads block while audio downloads, so the host
//! pulls from its own feeder thread into a buffer, never from the render callback.

use std::io::{self, Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex};

use librespot_audio::StreamLoaderController;
use librespot_playback::decoder::{AudioDecoder, AudioPacket, SymphoniaDecoder};
use symphonia::core::io::MediaSource;

use crate::error::{Result, SpotifyError};

pub const SAMPLE_RATE: u32 = librespot_playback::SAMPLE_RATE;
pub const CHANNELS: u16 = librespot_playback::NUM_CHANNELS as u16;

/// Decoded packets, one after another.
pub(crate) trait PacketSource: Send {
    /// Interleaved stereo samples, or nothing at the end.
    fn next_packet(&mut self) -> Result<Option<Vec<f64>>>;
    /// Moves to `position_ms` and returns where it landed.
    fn seek(&mut self, position_ms: u32) -> Result<u32>;
    fn close(&mut self) {}
}

/// The gain that brings a track to Spotify's reference loudness without clipping its peak.
pub(crate) fn normalisation_factor(gain_db: f32, peak: f32) -> f32 {
    let factor = 10f32.powf(gain_db / 20.0);
    if peak > 0.0 && factor * peak > 1.0 {
        1.0 / peak
    } else {
        factor
    }
}

/// A track's audio as interleaved `f32` frames at 44.1 kHz stereo.
pub(crate) struct PcmStream<S> {
    source: S,
    pending: Vec<f32>,
    taken: usize,
    position_frames: u64,
    gain: f32,
    ended: bool,
    duration_ms: u32,
}

impl<S: PacketSource> PcmStream<S> {
    pub(crate) const fn new(source: S, duration_ms: u32, gain: f32) -> Self {
        Self {
            source,
            pending: Vec::new(),
            taken: 0,
            position_frames: 0,
            gain,
            ended: false,
            duration_ms,
        }
    }

    pub(crate) const fn duration_ms(&self) -> u32 {
        self.duration_ms
    }

    pub(crate) fn position_ms(&self) -> u32 {
        u32::try_from(self.position_frames * 1000 / u64::from(SAMPLE_RATE)).unwrap_or(u32::MAX)
    }

    /// Up to `max_frames` frames. Fewer only at the end; none once it has ended.
    pub(crate) fn read(&mut self, max_frames: u32) -> Result<Vec<f32>> {
        let wanted = max_frames as usize * usize::from(CHANNELS);
        let mut out = Vec::with_capacity(wanted);
        while out.len() < wanted {
            if self.taken == self.pending.len() {
                if self.ended {
                    break;
                }
                match self.source.next_packet()? {
                    Some(samples) => {
                        let gain = f64::from(self.gain);
                        self.pending.clear();
                        self.pending
                            .extend(samples.iter().map(|s| (s * gain).clamp(-1.0, 1.0) as f32));
                        self.taken = 0;
                    }
                    None => self.ended = true,
                }
                continue;
            }
            let take = (wanted - out.len()).min(self.pending.len() - self.taken);
            out.extend_from_slice(&self.pending[self.taken..self.taken + take]);
            self.taken += take;
        }
        self.position_frames += (out.len() / usize::from(CHANNELS)) as u64;
        Ok(out)
    }

    /// Moves to `position_ms` (clamped to the track) and returns where it landed.
    pub(crate) fn seek(&mut self, position_ms: u32) -> Result<u32> {
        let landed = self.source.seek(position_ms.min(self.duration_ms))?;
        self.pending.clear();
        self.taken = 0;
        self.ended = false;
        self.position_frames = u64::from(landed) * u64::from(SAMPLE_RATE) / 1000;
        Ok(landed)
    }

    pub(crate) fn close(&mut self) {
        self.ended = true;
        self.pending.clear();
        self.taken = 0;
        self.source.close();
    }
}

/// The last read error the decoder saw, so a decode failure caused by the connection reads as
/// a network error rather than a broken file.
type ReadFailure = Arc<Mutex<Option<io::ErrorKind>>>;

/// A window onto a reader that starts at `offset`: Spotify's Ogg files carry a header of their
/// own before the real stream.
pub(crate) struct Subfile<R> {
    inner: R,
    offset: u64,
    length: u64,
    failure: ReadFailure,
}

impl<R: Read + Seek> Subfile<R> {
    pub(crate) fn new(
        mut inner: R,
        offset: u64,
        length: u64,
        failure: ReadFailure,
    ) -> io::Result<Self> {
        inner.seek(SeekFrom::Start(offset))?;
        Ok(Self {
            inner,
            offset,
            length: length.saturating_sub(offset),
            failure,
        })
    }

    fn note<T>(&self, result: io::Result<T>) -> io::Result<T> {
        if let Err(error) = &result
            && let Ok(mut slot) = self.failure.lock()
        {
            *slot = Some(error.kind());
        }
        result
    }
}

impl<R: Read + Seek> Read for Subfile<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let result = self.inner.read(buf);
        self.note(result)
    }
}

impl<R: Read + Seek> Seek for Subfile<R> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let target = match pos {
            SeekFrom::Start(at) => SeekFrom::Start(at.saturating_add(self.offset)),
            SeekFrom::End(back) => SeekFrom::Start(
                self.offset
                    .saturating_add(self.length)
                    .saturating_add_signed(back)
                    .max(self.offset),
            ),
            current @ SeekFrom::Current(_) => current,
        };
        let result = self.inner.seek(target);
        let at = self.note(result)?;
        Ok(at.saturating_sub(self.offset))
    }
}

impl<R: Read + Seek + Send + Sync> MediaSource for Subfile<R> {
    fn is_seekable(&self) -> bool {
        true
    }

    fn byte_len(&self) -> Option<u64> {
        Some(self.length)
    }
}

/// librespot's decoder over the decrypted download.
pub(crate) struct LibrespotSource {
    pub decoder: SymphoniaDecoder,
    pub controller: StreamLoaderController,
    pub failure: ReadFailure,
}

impl LibrespotSource {
    fn decode_error(&self, detail: impl Into<String>) -> SpotifyError {
        let failure = self.failure.lock().ok().and_then(|mut slot| slot.take());
        match failure {
            Some(kind) => SpotifyError::network(format!("the audio stopped arriving ({kind})")),
            None => SpotifyError::unavailable(detail),
        }
    }
}

impl PacketSource for LibrespotSource {
    fn next_packet(&mut self) -> Result<Option<Vec<f64>>> {
        match self.decoder.next_packet() {
            Ok(Some((_, AudioPacket::Samples(samples)))) => Ok(Some(samples)),
            Ok(Some((_, AudioPacket::Raw(_)))) => {
                Err(SpotifyError::unavailable("the audio was not decoded"))
            }
            Ok(None) => Ok(None),
            Err(error) => Err(self.decode_error(error.to_string())),
        }
    }

    fn seek(&mut self, position_ms: u32) -> Result<u32> {
        self.controller.set_random_access_mode();
        let landed = self
            .decoder
            .seek(position_ms)
            .map_err(|error| self.decode_error(error.to_string()));
        self.controller.set_stream_mode();
        landed
    }

    fn close(&mut self) {
        self.controller.close();
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    /// Packets of 4 frames whose samples count up from 0.
    struct Counting {
        packets: u32,
        served: u32,
        fail_after: Option<u32>,
    }

    impl Counting {
        fn new(packets: u32) -> Self {
            Self {
                packets,
                served: 0,
                fail_after: None,
            }
        }
    }

    impl PacketSource for Counting {
        fn next_packet(&mut self) -> Result<Option<Vec<f64>>> {
            if self.fail_after == Some(self.served) {
                return Err(SpotifyError::network("gone"));
            }
            if self.served == self.packets {
                return Ok(None);
            }
            let start = f64::from(self.served * 8);
            self.served += 1;
            Ok(Some(
                (0..8).map(|i| (start + f64::from(i)) / 1000.0).collect(),
            ))
        }

        fn seek(&mut self, position_ms: u32) -> Result<u32> {
            self.served = 0;
            Ok(position_ms)
        }
    }

    #[test]
    fn reads_span_packets_and_keep_the_rest_for_next_time() {
        let mut stream = PcmStream::new(Counting::new(3), 1_000, 1.0);
        let first = stream.read(6).expect("read");
        assert_eq!(first.len(), 12);
        let second = stream.read(6).expect("read");
        assert_eq!(second.len(), 12);
        assert!((second[0] - 0.012).abs() < 1e-6);
        assert_eq!(stream.position_frames, 12);
    }

    #[test]
    fn the_end_returns_what_is_left_and_then_nothing() {
        let mut stream = PcmStream::new(Counting::new(1), 1_000, 1.0);
        assert_eq!(stream.read(100).expect("read").len(), 8);
        assert_eq!(stream.read(100).expect("read").len(), 0);
        assert_eq!(stream.read(100).expect("read").len(), 0);
    }

    #[test]
    fn seeking_drops_what_was_buffered_and_moves_the_position() {
        let mut stream = PcmStream::new(Counting::new(3), 60_000, 1.0);
        stream.read(2).expect("read");
        let landed = stream.seek(30_000).expect("seek");
        assert_eq!(landed, 30_000);
        assert_eq!(stream.position_ms(), 30_000);
        let after = stream.read(1).expect("read");
        assert!(after[0].abs() < 1e-6);
    }

    #[test]
    fn seeking_past_the_end_lands_at_the_end() {
        let mut stream = PcmStream::new(Counting::new(3), 5_000, 1.0);
        assert_eq!(stream.seek(9_000).expect("seek"), 5_000);
    }

    #[test]
    fn seeking_after_the_end_plays_again() {
        let mut stream = PcmStream::new(Counting::new(1), 5_000, 1.0);
        stream.read(100).expect("read");
        stream.read(100).expect("read");
        stream.seek(0).expect("seek");
        assert_eq!(stream.read(100).expect("read").len(), 8);
    }

    #[test]
    fn errors_reach_the_caller() {
        let mut source = Counting::new(3);
        source.fail_after = Some(1);
        let mut stream = PcmStream::new(source, 1_000, 1.0);
        assert!(stream.read(4).is_ok());
        assert!(matches!(stream.read(4), Err(SpotifyError::Network { .. })));
    }

    #[test]
    fn gain_scales_and_never_clips_past_full_scale() {
        let mut stream = PcmStream::new(Counting::new(1), 1_000, 500.0);
        let samples = stream.read(4).expect("read");
        assert!(samples.iter().all(|s| (-1.0..=1.0).contains(s)));
        assert!((samples[1] - 0.5).abs() < 1e-6);
    }

    #[test]
    fn closing_ends_the_stream() {
        let mut stream = PcmStream::new(Counting::new(3), 1_000, 1.0);
        stream.close();
        assert_eq!(stream.read(4).expect("read").len(), 0);
    }

    #[test]
    fn normalisation_brings_loud_tracks_down_and_respects_the_peak() {
        assert!((normalisation_factor(-6.0, 0.5) - 0.501).abs() < 1e-3);
        assert!((normalisation_factor(6.0, 0.9) - 1.0 / 0.9).abs() < 1e-6);
        assert!((normalisation_factor(0.0, 1.0) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn a_subfile_hides_the_header_before_its_offset() {
        let data: Vec<u8> = (0..=255).collect();
        let failure = ReadFailure::default();
        let mut sub = Subfile::new(Cursor::new(data), 0xa7, 256, failure).expect("subfile");
        let mut first = [0u8; 1];
        sub.read_exact(&mut first).expect("read");
        assert_eq!(first[0], 0xa7);
        assert_eq!(sub.seek(SeekFrom::Start(0)).expect("seek"), 0);
        assert_eq!(sub.seek(SeekFrom::End(0)).expect("seek"), 256 - 0xa7);
        assert_eq!(sub.byte_len(), Some(256 - 0xa7));
    }

    struct Broken;

    impl Read for Broken {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            Err(io::ErrorKind::TimedOut.into())
        }
    }

    impl Seek for Broken {
        fn seek(&mut self, _: SeekFrom) -> io::Result<u64> {
            Ok(0)
        }
    }

    #[test]
    fn a_subfile_remembers_why_a_read_failed() {
        let failure = ReadFailure::default();
        let mut sub = Subfile::new(Broken, 0, 10, Arc::clone(&failure)).expect("subfile");
        let mut buf = [0u8; 4];
        assert!(sub.read(&mut buf).is_err());
        assert_eq!(
            *failure.lock().expect("lock"),
            Some(io::ErrorKind::TimedOut)
        );
    }
}
