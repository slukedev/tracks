//! Lidarr addresses as people type them, and its queue read past its first page.

use tracks_catalog::Response;
use tracks_catalog::lidarr::{self, Lidarr, QUEUE_PAGE_LIMIT, QUEUE_PAGE_SIZE, base_url};

fn queue_body(first_id: u32, count: u32, page: u32, total: u64) -> Response {
    let records: Vec<String> = (first_id..first_id + count)
        .map(|id| format!(r#"{{"id":{id},"title":"Item {id}","status":"downloading","size":100,"sizeleft":50}}"#))
        .collect();
    let body = format!(
        r#"{{"page":{page},"pageSize":{QUEUE_PAGE_SIZE},"totalRecords":{total},"records":[{}]}}"#,
        records.join(",")
    );
    Response::new(200, body.into_bytes())
}

#[test]
fn an_address_without_a_scheme_is_reached_over_http() {
    assert_eq!(base_url("nas:8686").as_deref(), Some("http://nas:8686"));
    assert_eq!(
        base_url("192.168.1.20:8686/").as_deref(),
        Some("http://192.168.1.20:8686")
    );
    assert_eq!(
        base_url("[fe80::1]:8686").as_deref(),
        Some("http://[fe80::1]:8686")
    );
}

#[test]
fn an_address_keeps_the_scheme_typed() {
    assert_eq!(
        base_url(" HTTPS://lidarr.example.com/ ").as_deref(),
        Some("https://lidarr.example.com")
    );
    assert_eq!(
        base_url("http://nas/lidarr").as_deref(),
        Some("http://nas/lidarr")
    );
}

#[test]
fn an_address_pasted_with_the_api_path_loses_it() {
    assert_eq!(
        base_url("http://nas:8686/api/v1").as_deref(),
        Some("http://nas:8686")
    );
    assert_eq!(
        base_url("nas:8686/API/").as_deref(),
        Some("http://nas:8686")
    );
    assert_eq!(
        base_url("http://nas/lidarr/api/v1/").as_deref(),
        Some("http://nas/lidarr")
    );
}

#[test]
fn an_address_that_cannot_be_lidarr_is_refused() {
    for typed in [
        "",
        "   ",
        "/",
        "ftp://nas:8686",
        "nas://8686",
        "http://",
        "https:///api",
    ] {
        assert_eq!(base_url(typed), None, "{typed:?}");
    }
}

#[test]
fn requests_go_under_the_address_once() {
    let api = Lidarr::new("nas:8686/api/v1", "k");
    assert_eq!(api.status().url, "http://nas:8686/api/v1/system/status");
}

#[test]
fn the_queue_is_asked_for_a_page_at_a_time() {
    let api = Lidarr::new("http://nas:8686", "k");
    let second = api.queue_page(2);
    assert!(second.url.contains("page=2"), "{}", second.url);
    assert!(
        second.url.contains(&format!("pageSize={QUEUE_PAGE_SIZE}")),
        "{}",
        second.url
    );
    assert!(api.queue_page(0).url.contains("page=1"));
}

#[test]
fn a_full_page_with_more_behind_it_asks_for_the_next() {
    let page =
        lidarr::parse_queue_page(&queue_body(1, QUEUE_PAGE_SIZE, 1, 250), 1).expect("readable");
    assert_eq!(page.items.len(), QUEUE_PAGE_SIZE as usize);
    assert_eq!(page.next, Some(2));
}

#[test]
fn every_item_past_the_first_hundred_is_read() {
    let total = 250_u64;
    let mut ids = Vec::new();
    let mut asked = Some(1_u32);
    while let Some(number) = asked {
        let first = (number - 1) * QUEUE_PAGE_SIZE + 1;
        let left = u32::try_from(total).expect("small") + 1 - first;
        let page = lidarr::parse_queue_page(
            &queue_body(first, left.min(QUEUE_PAGE_SIZE), number, total),
            number,
        )
        .expect("readable");
        ids.extend(page.items.iter().map(|item| item.id));
        asked = page.next;
    }
    assert_eq!(ids.len(), 250);
    assert_eq!(ids.last(), Some(&250));
}

#[test]
fn a_short_page_is_the_last_whatever_the_total_says() {
    let page = lidarr::parse_queue_page(&queue_body(201, 30, 3, 900), 3).expect("readable");
    assert_eq!(page.next, None);
}

#[test]
fn the_queue_stops_at_its_page_limit() {
    let page = lidarr::parse_queue_page(
        &queue_body(1, QUEUE_PAGE_SIZE, QUEUE_PAGE_LIMIT, 1_000_000),
        QUEUE_PAGE_LIMIT,
    )
    .expect("readable");
    assert_eq!(page.next, None);
}

#[test]
fn a_page_without_a_total_reads_on_while_it_is_full() {
    let body = format!(
        r#"{{"records":[{}]}}"#,
        (1..=QUEUE_PAGE_SIZE)
            .map(|id| format!(r#"{{"id":{id}}}"#))
            .collect::<Vec<_>>()
            .join(",")
    );
    let page =
        lidarr::parse_queue_page(&Response::new(200, body.into_bytes()), 1).expect("readable");
    assert_eq!(page.next, Some(2));
}

#[test]
fn a_picture_offered_over_plain_http_is_asked_for_over_https() {
    let body = r#"[{"id":0,"artistName":"Wet Leg","foreignArtistId":"x","images":[{"coverType":"poster","remoteUrl":"http://assets.fanart.tv/a.jpg"}]}]"#;
    let artists =
        lidarr::parse_artists(&Response::new(200, body.as_bytes().to_vec())).expect("readable");
    assert_eq!(
        artists[0].picture_url.as_deref(),
        Some("https://assets.fanart.tv/a.jpg")
    );
}
