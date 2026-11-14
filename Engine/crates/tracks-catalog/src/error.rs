/// Why a catalog or Lidarr answer couldn't be used.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CatalogError {
    /// The key was refused (401).
    #[error("the API key was refused")]
    WrongKey,
    /// The service said why it wouldn't: "This artist has already been added".
    #[error("{0}")]
    Rejected(String),
    /// Not the service asked for: the wrong address or port, most likely.
    #[error("the answer wasn't from the expected service")]
    WrongService,
    /// Any other HTTP failure.
    #[error("HTTP {status}")]
    Http { status: u16 },
}

impl CatalogError {
    /// Whether asking again later may work.
    pub fn is_retryable(&self) -> bool {
        matches!(self, Self::Http { status } if *status == 429 || *status >= 500)
    }
}

pub type Result<T> = std::result::Result<T, CatalogError>;
