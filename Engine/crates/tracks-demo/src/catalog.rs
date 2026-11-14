//! The invented catalogue: every artist, album and song name, and who sings with whom.

use crate::random::Draw;

/// One invented song.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Song {
    pub title: String,
    pub artist: String,
    pub album: String,
    /// Seconds.
    pub length: f64,
}

#[derive(Debug, Clone)]
pub(crate) struct Album {
    pub songs: Vec<Song>,
}

#[derive(Debug, Clone)]
pub(crate) struct Artist {
    pub albums: Vec<Album>,
    pub weight: f64,
    /// How many days ago they first appear.
    pub debut_offset: i64,
}

#[derive(Debug, Clone)]
pub(crate) struct Catalog {
    pub artists: Vec<Artist>,
}

/// A song by more than one artist.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Collaboration {
    /// Where the song is: artist, album and song, by position.
    pub artist: usize,
    pub album: usize,
    pub song: usize,
    /// The song's whole artist credit, when it isn't the artist alone.
    pub credit: Option<&'static str>,
    /// Someone featured in the title, as Apple Music writes it.
    pub title_feature: Option<&'static str>,
    /// Who Apple Music would list as the song's artists.
    pub catalog: &'static [&'static str],
}

/// Real Apple Music genre names, handed out by artist.
pub(crate) const GENRES: [&str; 10] = [
    "Indie Pop",
    "Alternative",
    "R&B/Soul",
    "Electronic",
    "Hip-Hop/Rap",
    "Singer/Songwriter",
    "Pop",
    "Jazz",
    "Dance",
    "Folk",
];

pub(crate) const ARTIST_NAMES: [&str; 32] = [
    "Mara Solis",
    "Juniper Lane",
    "Nova Harbor",
    "Wren & Ivy",
    "Hollow Pines",
    "The Velvet Hours",
    "Solstice Club",
    "Lumen Drive",
    "Amara Vale",
    "Northern Static",
    "Kaito Rivers",
    "Ada Kestrel",
    "Blue Arcade",
    "Paper Moons",
    "Sierra Hale",
    "Tidal Bloom",
    "Orchid Club",
    "Rowan Ellis",
    "Silver Coast",
    "Ivory Tapes",
    "Luca Marin",
    "Fern & Fable",
    "Neon Meridian",
    "Cassia Ray",
    "The Quiet Year",
    "Mika Sol",
    "Atlas Grove",
    "June Reverie",
    "Harbor Lights",
    "Elio Brandt",
    "Low Tide Society",
    "Margo Finch",
];

pub(crate) const ALBUM_NAMES: [&str; 62] = [
    "Tides",
    "Glasshouse",
    "Night Swimming Lessons",
    "Soft Focus",
    "Northbound",
    "Afterglow Radio",
    "Paper Houses",
    "Blue Hour",
    "Small Constellations",
    "Weathervane",
    "Island Time",
    "Static Bloom",
    "Monochrome Summer",
    "Holding Pattern",
    "The Long Weekend",
    "Salt & Honey",
    "Quiet Machines",
    "Open Water",
    "Satellite Hearts",
    "Ferris Wheel",
    "Wildflower Season",
    "Lanterns",
    "Undercurrent",
    "Parallel Lines",
    "Neon Garden",
    "Coastal Drive",
    "Late Bloomer",
    "Silver Linings Club",
    "Kaleidoscope Morning",
    "Echo Park Diaries",
    "Postcards",
    "Glow",
    "Faraway Friends",
    "Moth & Flame",
    "Daylight Savings",
    "Evergreen",
    "Low Light",
    "Porchlight",
    "Heatwave",
    "First Frost",
    "Golden Static",
    "Driftwood",
    "Midnight Bakery",
    "Paper Crowns",
    "Slow Dance Club",
    "Overpass",
    "Harbour Songs",
    "Night Market",
    "Almanac",
    "Second Nature",
    "Tall Grass",
    "Mirror Lake",
    "Cloud Club",
    "Seasonal",
    "Room Tone",
    "Weekend Weather",
    "Softly, Softly",
    "Longhand",
    "The Green Room",
    "Sunroom",
    "Distant Shores",
    "Field Recordings",
];

pub(crate) const SONG_NAMES: [&str; 128] = [
    "Saltwater",
    "Undertow",
    "Paper Moon",
    "Glass Heart",
    "Night Drive",
    "Weightless",
    "Coastline",
    "Velvet Signal",
    "Late Summer",
    "Satellite",
    "Orange Sky",
    "Northern Lights",
    "Slow Motion",
    "Honey",
    "Neon Tide",
    "Afterimage",
    "Wildfire",
    "Porch Swing",
    "Moonlit",
    "Echoes",
    "Daydream",
    "Ferris Wheel",
    "Lighthouse",
    "Parallel",
    "Constellations",
    "Borrowed Time",
    "Still Water",
    "Heatwave",
    "Denim Weather",
    "Kaleidoscope",
    "Firefly",
    "Open Road",
    "Marigold",
    "Quiet Hours",
    "Silver Lining",
    "Paper Boats",
    "Overpass",
    "Weathervane",
    "Fever Dream",
    "Wanderer",
    "Evergreen",
    "Cinnamon",
    "Dandelion",
    "Postcard",
    "Low Light",
    "Sunday Morning Radio",
    "Tangerine",
    "Soft Spot",
    "Starling",
    "Rewind",
    "Horizon Line",
    "Headlights",
    "Gravity",
    "Polaroid",
    "Undone",
    "Ghost Town Glow",
    "Lemonade Sky",
    "Driftwood",
    "Midnight Bakery",
    "Aurora",
    "Night Train Home",
    "Golden",
    "Hummingbird",
    "Seaglass",
    "Rooftops",
    "Lanterns",
    "Butterflies",
    "Moth",
    "Ivy",
    "Wavelength",
    "Static",
    "Afterparty",
    "Wild Run",
    "Wintergreen",
    "Blueprint",
    "Echo Park",
    "Faraway",
    "Sparrow",
    "Carousel",
    "Riverbed",
    "Nightshade",
    "Brighter",
    "Fool's Gold",
    "Lullaby",
    "Paper Crown",
    "Rosewater",
    "Skyline",
    "Tidal",
    "Glow",
    "Swim",
    "Wool & Weather",
    "Cherry Cola",
    "Hometown",
    "Shoreline",
    "Violet",
    "Disco Moon",
    "Summer Rain",
    "Hurricane",
    "Neon Lights",
    "Almost Home",
    "Stargazer",
    "Backseat",
    "Satellite Hearts",
    "Crystal Clear",
    "Wildflowers",
    "Cold Coffee",
    "Late Checkout",
    "Glass Town",
    "Dreamland",
    "Blue Hour",
    "Moonrise",
    "Deep End",
    "Radio Silence",
    "Pine",
    "Wander",
    "Afterglow",
    "Open Ocean",
    "Rain Check",
    "Say It Twice",
    "Second Guess",
    "Slow Burn",
    "Ten Past Two",
    "Take the Long Way",
    "Tell Me Softly",
    "The Quiet Part",
    "Too Late to Call",
    "Waiting Room",
    "Worn Out Shoes",
];

/// Songs by more than one artist, one of each kind of credit.
pub(crate) const COLLABORATIONS: [Collaboration; 7] = [
    Collaboration {
        artist: 0,
        album: 1,
        song: 2,
        credit: Some("Mara Solis & Nova Harbor"),
        title_feature: None,
        catalog: &["Mara Solis", "Nova Harbor"],
    },
    Collaboration {
        artist: 1,
        album: 0,
        song: 3,
        credit: None,
        title_feature: Some("Kaito Rivers"),
        catalog: &["Juniper Lane", "Kaito Rivers"],
    },
    Collaboration {
        artist: 2,
        album: 1,
        song: 0,
        credit: Some("Nova Harbor feat. Ada Kestrel"),
        title_feature: None,
        catalog: &["Nova Harbor", "Ada Kestrel"],
    },
    Collaboration {
        artist: 3,
        album: 0,
        song: 2,
        credit: Some("Wren & Ivy feat. Mara Solis"),
        title_feature: None,
        catalog: &["Wren & Ivy", "Mara Solis"],
    },
    Collaboration {
        artist: 4,
        album: 0,
        song: 2,
        credit: Some("Hollow Pines, Amara Vale, Luca Marin & Cassia Ray"),
        title_feature: None,
        catalog: &["Hollow Pines", "Amara Vale", "Luca Marin", "Cassia Ray"],
    },
    Collaboration {
        artist: 5,
        album: 0,
        song: 1,
        credit: Some("The Velvet Hours x Mara Solis"),
        title_feature: None,
        catalog: &["The Velvet Hours", "Mara Solis"],
    },
    Collaboration {
        artist: 6,
        album: 0,
        song: 2,
        credit: None,
        title_feature: Some("Anastasia Montgomery-Whitfield"),
        catalog: &["Solstice Club", "Anastasia Montgomery-Whitfield"],
    },
];

impl Catalog {
    /// Deals out album and song names to every artist, then applies the collaborations.
    pub(crate) fn make(random: &mut Draw) -> Self {
        let mut album_pool: Vec<&str> = ALBUM_NAMES.to_vec();
        random.shuffle(&mut album_pool);
        let mut album_pool = album_pool.into_iter();
        let mut song_pool = shuffled_songs(random);
        let mut artists = Vec::with_capacity(ARTIST_NAMES.len());

        for (rank, name) in ARTIST_NAMES.iter().enumerate() {
            let album_count = match rank {
                0..8 => 3,
                8..20 => 2,
                _ => 1,
            };
            let mut albums: Vec<Album> = Vec::with_capacity(album_count);
            for _ in 0..album_count {
                // 60 album slots and 62 names, so no two artists share an album title.
                let title = album_pool
                    .next()
                    .map_or_else(|| format!("Untitled {}", albums.len() + 1), str::to_owned);
                let count = random.int(4, 7);
                let songs = (0..count)
                    .map(|_| {
                        if song_pool.is_empty() {
                            song_pool = shuffled_songs(random);
                        }
                        // Taken from the end, which is as random as the front of a shuffle.
                        let song_title = song_pool.pop().unwrap_or_default();
                        Song {
                            title: song_title.to_owned(),
                            artist: (*name).to_owned(),
                            album: title.clone(),
                            length: random.range(150.0, 290.0),
                        }
                    })
                    .collect();
                albums.push(Album { songs });
            }
            // Favourites have always been around; the long tail arrives over the year.
            let debut_offset = if rank < 10 {
                400
            } else {
                random.int(5, 280) as i64
            };
            artists.push(Artist {
                albums,
                weight: 1.0 / ((rank + 1) as f64).powf(0.95),
                debut_offset,
            });
        }

        // After every draw, so the history around them stays as it was.
        for collaboration in &COLLABORATIONS {
            let song = &mut artists[collaboration.artist].albums[collaboration.album].songs
                [collaboration.song];
            if let Some(feature) = collaboration.title_feature {
                song.title = format!("{} (feat. {feature})", song.title);
            }
            if let Some(credit) = collaboration.credit {
                credit.clone_into(&mut song.artist);
            }
        }
        Self { artists }
    }
}

/// The catalog's artists for a song, by its artist credit and title.
pub(crate) fn catalog_artists(song: &Song) -> Vec<String> {
    COLLABORATIONS
        .iter()
        .find(|c| {
            c.credit == Some(song.artist.as_str())
                || c.title_feature
                    .is_some_and(|feature| song.title.ends_with(&format!("(feat. {feature})")))
        })
        .map_or_else(
            || vec![song.artist.clone()],
            |c| c.catalog.iter().map(|&name| name.to_owned()).collect(),
        )
}

fn shuffled_songs(random: &mut Draw) -> Vec<&'static str> {
    let mut pool = SONG_NAMES.to_vec();
    random.shuffle(&mut pool);
    pool
}
