use std::fmt;
use std::str::FromStr;

/// A stored value this version doesn't know.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("unknown value `{0}`")]
pub struct UnknownValue(pub String);

/// How a song came to be in the history. The raw values are the ones the Swift app stores.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[repr(u8)]
pub enum PlayKind {
    /// Heard on a radio station.
    Radio = 0,
    /// Played on demand.
    OnDemand = 1,
    /// Read from Apple Music's recently played list, dated when found rather than when played.
    Imported = 2,
    /// Read from the connected Last.fm account's scrobbles.
    LastFm = 3,
}

impl PlayKind {
    pub const ALL: [Self; 4] = [Self::Radio, Self::OnDemand, Self::Imported, Self::LastFm];

    /// Whether the play's time is when it played. Imports are dated when they were found.
    pub const fn time_is_known(self) -> bool {
        !matches!(self, Self::Imported)
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Radio => "radio",
            Self::OnDemand => "onDemand",
            Self::Imported => "imported",
            Self::LastFm => "lastFM",
        }
    }

    pub const fn from_u8(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Radio),
            1 => Some(Self::OnDemand),
            2 => Some(Self::Imported),
            3 => Some(Self::LastFm),
            _ => None,
        }
    }
}

impl FromStr for PlayKind {
    type Err = UnknownValue;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::ALL
            .into_iter()
            .find(|kind| kind.as_str() == s)
            .ok_or_else(|| UnknownValue(s.to_owned()))
    }
}

impl fmt::Display for PlayKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// Where a play's music came from.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub enum PlaySource {
    /// Apple Music, and every play kept before Tracks knew the difference.
    #[default]
    AppleMusic,
    /// Files and servers Tracks plays itself.
    YourMusic,
    Spotify,
    /// A source from a newer build, kept exactly as stored so it syncs back unchanged and is
    /// never counted as one of the others.
    Unknown(String),
}

impl PlaySource {
    /// The index's code for a source this build doesn't know; its stored value is kept
    /// alongside.
    pub const UNKNOWN_CODE: u8 = u8::MAX;

    /// Reads a stored value. Nothing stored means Apple Music, as it did before there was a
    /// choice; anything this build doesn't know stays as it was.
    pub fn from_stored(raw: Option<&str>) -> Self {
        match raw {
            None | Some("" | "appleMusic") => Self::AppleMusic,
            Some("yourMusic") => Self::YourMusic,
            Some("spotify") => Self::Spotify,
            Some(other) => Self::Unknown(other.to_owned()),
        }
    }

    /// The value the hosts store.
    pub fn as_str(&self) -> &str {
        match self {
            Self::AppleMusic => "appleMusic",
            Self::YourMusic => "yourMusic",
            Self::Spotify => "spotify",
            Self::Unknown(raw) => raw,
        }
    }

    /// The index's code. Apple Music and Your Music keep the codes they always had.
    pub const fn code(&self) -> u8 {
        match self {
            Self::AppleMusic => 0,
            Self::YourMusic => 1,
            Self::Spotify => 2,
            Self::Unknown(_) => Self::UNKNOWN_CODE,
        }
    }

    /// Reads a source back from the index's code and, for an unknown one, its stored value.
    pub fn from_code(code: u8, stored: Option<&str>) -> Self {
        match code {
            0 => Self::AppleMusic,
            1 => Self::YourMusic,
            2 => Self::Spotify,
            _ => Self::Unknown(stored.unwrap_or_default().to_owned()),
        }
    }
}

impl fmt::Display for PlaySource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_round_trip_through_their_stored_values() {
        for kind in PlayKind::ALL {
            assert_eq!(kind.as_str().parse::<PlayKind>(), Ok(kind));
            assert_eq!(PlayKind::from_u8(kind as u8), Some(kind));
        }
        assert!("someday".parse::<PlayKind>().is_err());
    }

    #[test]
    fn missing_sources_are_apple_music() {
        assert_eq!(PlaySource::from_stored(None), PlaySource::AppleMusic);
        assert_eq!(PlaySource::from_stored(Some("")), PlaySource::AppleMusic);
    }

    #[test]
    fn known_sources_round_trip_through_their_stored_values() {
        for source in [
            PlaySource::AppleMusic,
            PlaySource::YourMusic,
            PlaySource::Spotify,
        ] {
            assert_eq!(PlaySource::from_stored(Some(source.as_str())), source);
            assert_eq!(PlaySource::from_code(source.code(), None), source);
        }
    }

    #[test]
    fn existing_stored_values_and_codes_are_unchanged() {
        assert_eq!(PlaySource::AppleMusic.as_str(), "appleMusic");
        assert_eq!(PlaySource::YourMusic.as_str(), "yourMusic");
        assert_eq!(PlaySource::AppleMusic.code(), 0);
        assert_eq!(PlaySource::YourMusic.code(), 1);
    }

    #[test]
    fn unknown_sources_keep_their_stored_value() {
        let source = PlaySource::from_stored(Some("vinyl"));
        assert_eq!(source, PlaySource::Unknown("vinyl".into()));
        assert_eq!(source.as_str(), "vinyl");
        assert_eq!(source.code(), PlaySource::UNKNOWN_CODE);
        assert_eq!(PlaySource::from_code(source.code(), Some("vinyl")), source);
    }

    #[test]
    fn unknown_sources_are_never_counted_as_known_ones() {
        assert_ne!(
            PlaySource::from_stored(Some("AppleMusic")),
            PlaySource::AppleMusic
        );
        assert_ne!(
            PlaySource::from_stored(Some("Spotify")),
            PlaySource::Spotify
        );
    }
}
