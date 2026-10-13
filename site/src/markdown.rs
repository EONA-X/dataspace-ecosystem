//! Markdown to HTML, with GitHub-style heading ids and links rewritten for the site.

use std::collections::HashMap;

use pulldown_cmark::{html, CowStr, Event, HeadingLevel, Options, Parser, Tag, TagEnd};

/// Where a link or image found in a page points once the site is built.
pub struct Links<'a> {
    /// Repository-relative Markdown path of every generated page, to its URL.
    pub pages: &'a HashMap<String, String>,
    /// URL of the page being rendered, relative to the site root.
    pub page_url: &'a str,
    /// Repository-relative directory of the page being rendered.
    pub source_dir: &'a str,
    /// Blob URL prefix of the repository, no trailing slash.
    pub repo_url: &'a str,
    /// Checked to tell files from directories.
    pub repo_root: &'a std::path::Path,
}

/// A fenced or indented code block, set aside to be rendered by the toolkit.
pub struct CodeSnippet {
    pub language: String,
    pub code: String,
}

pub struct Rendered {
    /// First level-1 heading, if any.
    pub title: Option<String>,
    /// HTML with a `<!--code:N-->` marker standing for `snippets[N]`.
    pub html: String,
    pub snippets: Vec<CodeSnippet>,
    pub warnings: Vec<String>,
}

pub fn render(source: &str, links: &Links) -> Rendered {
    let options = Options::ENABLE_TABLES
        | Options::ENABLE_FOOTNOTES
        | Options::ENABLE_STRIKETHROUGH
        | Options::ENABLE_TASKLISTS;
    let mut events: Vec<Event> = Parser::new_ext(source, options).collect();

    let title = add_heading_ids(&mut events);

    let mut warnings = Vec::new();
    let mut snippets = Vec::new();
    let mut out: Vec<Event> = Vec::with_capacity(events.len());
    let mut code: Option<CodeSnippet> = None;
    let mut skip_image = false;

    for event in events {
        match event {
            Event::Start(Tag::CodeBlock(kind)) => {
                let language = match kind {
                    pulldown_cmark::CodeBlockKind::Fenced(info) => info
                        .split(|c: char| c.is_whitespace() || c == ',')
                        .next()
                        .filter(|l| !l.is_empty())
                        .unwrap_or("text")
                        .to_string(),
                    pulldown_cmark::CodeBlockKind::Indented => "text".to_string(),
                };
                code = Some(CodeSnippet { language, code: String::new() });
            }
            Event::Text(text) if code.is_some() => {
                code.as_mut().expect("checked above").code.push_str(&text);
            }
            Event::End(TagEnd::CodeBlock) => {
                if let Some(mut snippet) = code.take() {
                    snippet.code.truncate(snippet.code.trim_end().len());
                    out.push(Event::Html(format!("<!--code:{}-->", snippets.len()).into()));
                    snippets.push(snippet);
                }
            }
            // A badge left with an empty target would render as a broken image.
            Event::Start(Tag::Image { ref dest_url, .. }) if dest_url.is_empty() => skip_image = true,
            Event::End(TagEnd::Image) if skip_image => skip_image = false,
            _ if skip_image => {}
            Event::Start(Tag::Link { link_type, dest_url, title, id }) => {
                let dest_url = rewrite(&dest_url, false, links, &mut warnings);
                out.push(Event::Start(Tag::Link { link_type, dest_url, title, id }));
            }
            Event::Start(Tag::Image { link_type, dest_url, title, id }) => {
                let dest_url = rewrite(&dest_url, true, links, &mut warnings);
                out.push(Event::Start(Tag::Image { link_type, dest_url, title, id }));
            }
            other => out.push(other),
        }
    }

    let mut html = String::new();
    html::push_html(&mut html, out.into_iter());
    Rendered { title, html, snippets, warnings }
}

/// Gives every heading a GitHub-style id (`## Two words` becomes `two-words`,
/// repeats get `-1`, `-2`), so anchors written for GitHub keep working.
/// Returns the text of the first level-1 heading.
fn add_heading_ids(events: &mut [Event]) -> Option<String> {
    let mut seen: HashMap<String, usize> = HashMap::new();
    let mut title = None;
    let mut i = 0;
    while i < events.len() {
        if let Event::Start(Tag::Heading { level, .. }) = &events[i] {
            let level = *level;
            let mut text = String::new();
            let mut j = i + 1;
            while j < events.len() && !matches!(events[j], Event::End(TagEnd::Heading(_))) {
                if let Event::Text(t) | Event::Code(t) = &events[j] {
                    text.push_str(t);
                }
                j += 1;
            }
            let base = slugify(&text);
            let count = seen.entry(base.clone()).or_insert(0);
            let slug = if *count == 0 { base } else { format!("{base}-{count}") };
            *count += 1;
            if level == HeadingLevel::H1 && title.is_none() {
                title = Some(text);
            }
            if let Event::Start(Tag::Heading { id, .. }) = &mut events[i] {
                if id.is_none() {
                    *id = Some(CowStr::from(slug));
                }
            }
        }
        i += 1;
    }
    title
}

fn slugify(text: &str) -> String {
    text.trim()
        .to_lowercase()
        .chars()
        .filter_map(|c| match c {
            c if c.is_alphanumeric() || c == '-' || c == '_' => Some(c),
            c if c.is_whitespace() => Some('-'),
            _ => None,
        })
        .collect()
}

/// External links stay as they are. A link to a generated page becomes that page's
/// URL; any other repository path becomes a link into the hosted repository.
fn rewrite<'a>(dest: &str, image: bool, links: &Links, warnings: &mut Vec<String>) -> CowStr<'a> {
    if dest.is_empty() || dest.contains("://") || dest.starts_with("mailto:") || dest.starts_with("tel:") {
        return dest.to_string().into();
    }
    // The pages set a <base href>, so a bare fragment would resolve against the
    // site root instead of the page.
    if dest.starts_with('#') {
        return format!("{}{dest}", links.page_url).into();
    }

    let (path, fragment) = match dest.split_once('#') {
        Some((path, fragment)) => (path, format!("#{fragment}")),
        None => (dest, String::new()),
    };
    let joined = if let Some(rooted) = path.strip_prefix('/') {
        rooted.to_string()
    } else if links.source_dir.is_empty() {
        path.to_string()
    } else {
        format!("{}/{path}", links.source_dir)
    };
    let Some(target) = normalize(&joined) else {
        warnings.push(format!("link leaves the repository: {dest}"));
        return dest.to_string().into();
    };

    if let Some(url) = links.pages.get(&target) {
        return format!("{url}{fragment}").into();
    }

    let on_disk = links.repo_root.join(&target);
    if !on_disk.exists() {
        warnings.push(format!("link target does not exist: {dest}"));
    }
    let base = links.repo_url.trim_end_matches('/');
    let url = if image {
        base.replace("/blob/", "/raw/")
    } else if on_disk.is_dir() {
        base.replace("/blob/", "/tree/")
    } else {
        base.to_string()
    };
    format!("{url}/{target}{fragment}").into()
}

/// Resolves `.` and `..`; `None` when the path climbs out of the repository.
fn normalize(path: &str) -> Option<String> {
    let mut parts: Vec<&str> = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop()?;
            }
            part => parts.push(part),
        }
    }
    Some(parts.join("/"))
}
