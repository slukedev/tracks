//! The request the host sends as is, and what it got back.

/// An HTTP method.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Method {
    Get,
    Post,
    Put,
}

impl Method {
    /// The method's name as it goes on the wire.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Get => "GET",
            Self::Post => "POST",
            Self::Put => "PUT",
        }
    }
}

/// A request for the host to send exactly as built.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Request {
    pub method: Method,
    pub url: String,
    /// Header names and values, in order.
    pub headers: Vec<(String, String)>,
    /// The body, empty for none.
    pub body: Vec<u8>,
}

impl Request {
    pub(crate) fn get(url: String) -> Self {
        Self {
            method: Method::Get,
            url,
            headers: vec![("Accept".to_owned(), "application/json".to_owned())],
            body: Vec::new(),
        }
    }

    /// The value of the first header with this name, ignoring case.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }
}

/// What came back. A transport failure is the host's to report, never one of these.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Response {
    pub status: u16,
    pub body: Vec<u8>,
}

impl Response {
    pub fn new(status: u16, body: impl Into<Vec<u8>>) -> Self {
        Self {
            status,
            body: body.into(),
        }
    }

    pub(crate) fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }
}

/// Percent-encodes a query value: everything but unreserved characters, so "+" and "&" in a
/// name ("Florence + the Machine") reach the server as written.
pub fn query_escape(value: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(value.len());
    for &byte in value.as_bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            out.push(char::from(byte));
        } else {
            out.push('%');
            out.push(char::from(HEX[usize::from(byte >> 4)]));
            out.push(char::from(HEX[usize::from(byte & 0x0F)]));
        }
    }
    out
}

/// `name=value` pairs joined into a query, in the order given.
pub(crate) fn query(pairs: &[(&str, &str)]) -> String {
    pairs
        .iter()
        .map(|(name, value)| format!("{}={}", query_escape(name), query_escape(value)))
        .collect::<Vec<_>>()
        .join("&")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escapes_everything_a_name_can_hold() {
        assert_eq!(query_escape("Florence + the Machine"), "Florence%20%2B%20the%20Machine");
        assert_eq!(query_escape("AC/DC & Björk"), "AC%2FDC%20%26%20Bj%C3%B6rk");
        assert_eq!(query_escape("a-b_c.d~e"), "a-b_c.d~e");
    }
}
