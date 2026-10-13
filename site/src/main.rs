//! Renders the repository's Markdown into a static documentation site.
//!
//! `cargo run --release` writes the site to `dist/`; `cargo run -- --check` only
//! verifies that `nav.toml` accounts for every tracked Markdown file.
//!
//! Environment:
//! - `SITE_BASE`: URL path the site is served under, e.g. `/dataspace-ecosystem/`.
//!   Unset, every page links its assets relative to itself, so `dist/` also opens
//!   straight from disk.
//! - `SITE_REPO_URL`: blob URL of the hosted repository, used for links to files that
//!   are not pages. Defaults to the GitHub mirror's `main` branch.
//! - `SITE_REPO_ROOT`: the repository checkout, `..` by default.

mod markdown;
mod nav;
mod page;

use std::{
    collections::{BTreeSet, HashMap},
    env,
    error::Error,
    fs,
    path::{Path, PathBuf},
    process::{Command, ExitCode},
};

use eona_ui_toolkit::{COMPONENTS_CSS, LOGO_SVG, TOKENS_CSS};
use eona_ui_toolkit::demo::DEMO_JS;

use nav::Nav;
use page::{NavGroup, NavItem, NavSection, PageProps};

const DEFAULT_REPO_URL: &str = "https://github.com/EONA-X/dataspace-ecosystem/blob/main";

type Result<T> = std::result::Result<T, Box<dyn Error>>;

#[tokio::main(flavor = "current_thread")]
async fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    let check = args.iter().any(|a| a == "--check");
    let out = args
        .iter()
        .position(|a| a == "--out")
        .and_then(|i| args.get(i + 1))
        .map_or_else(|| PathBuf::from("dist"), PathBuf::from);

    let root = PathBuf::from(env::var("SITE_REPO_ROOT").unwrap_or_else(|_| "..".into()));
    let result = match Nav::load(Path::new("nav.toml")) {
        Ok(nav) if check => check_nav(&nav, &root),
        Ok(nav) => build(&nav, &root, &out).await,
        Err(e) => Err(e),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Fails when a tracked Markdown file is neither in the navigation nor excluded, or
/// when the navigation names a file that is missing or excluded.
fn check_nav(nav: &Nav, root: &Path) -> Result<()> {
    let listing = Command::new("git").arg("-C").arg(root).args(["ls-files", "*.md", "*.MD"]).output()?;
    if !listing.status.success() {
        return Err(format!("git ls-files failed: {}", String::from_utf8_lossy(&listing.stderr)).into());
    }
    let tracked: BTreeSet<String> = String::from_utf8(listing.stdout)?
        .lines()
        .filter(|l| l.to_ascii_lowercase().ends_with(".md"))
        .map(str::to_string)
        .collect();
    let listed: BTreeSet<&str> = nav.entries().map(|(_, e)| e.path.as_str()).collect();

    let mut problems = Vec::new();
    for path in &tracked {
        if !listed.contains(path.as_str()) && !nav.is_excluded(path) {
            problems.push(format!("{path}: tracked but neither in nav.toml nor excluded"));
        }
    }
    for path in &listed {
        if !tracked.contains(*path) {
            problems.push(format!("{path}: in nav.toml but not a tracked Markdown file"));
        }
        if nav.is_excluded(path) {
            problems.push(format!("{path}: both in nav.toml and excluded"));
        }
    }

    if problems.is_empty() {
        println!("nav.toml accounts for all {} tracked Markdown files", tracked.len());
        Ok(())
    } else {
        Err(problems.join("\n").into())
    }
}

/// Where a Markdown file lands: `dir/README.md` becomes `dir/index.html`, any other
/// `dir/name.md` becomes `dir/name.html`.
fn page_url(path: &str) -> String {
    let (dir, file) = path.rsplit_once('/').unwrap_or(("", path));
    let stem = file.rsplit_once('.').map_or(file, |(stem, _)| stem);
    let name = if stem.eq_ignore_ascii_case("readme") { "index".to_string() } else { stem.to_string() };
    if dir.is_empty() { format!("{name}.html") } else { format!("{dir}/{name}.html") }
}

/// A page's own `<base href>`: the configured site path, or the way back to the root.
fn base_href(url: &str, site_base: Option<&str>) -> String {
    match site_base {
        Some(base) => format!("{}/", base.trim_end_matches('/')),
        None => match url.matches('/').count() {
            0 => "./".to_string(),
            depth => "../".repeat(depth),
        },
    }
}

struct Source {
    path: String,
    url: String,
    text: String,
    section: String,
    nav_title: String,
}

async fn build(nav: &Nav, root: &Path, out: &Path) -> Result<()> {
    let site_base = env::var("SITE_BASE").ok().filter(|b| !b.is_empty() && b != "/");
    let repo_url = env::var("SITE_REPO_URL").unwrap_or_else(|_| DEFAULT_REPO_URL.to_string());
    let repo_home = match repo_url.split_once("/blob/") {
        Some((home, _)) => home.trim_end_matches("/-").to_string(),
        None => repo_url.clone(),
    };

    let mut sources: Vec<Source> = Vec::new();
    for (section, entry) in nav.entries() {
        let text = fs::read_to_string(root.join(&entry.path))
            .map_err(|e| format!("{}: {e}", entry.path))?;
        sources.push(Source {
            url: page_url(&entry.path),
            path: entry.path.clone(),
            text,
            section: section.title.clone(),
            nav_title: entry.title.clone().unwrap_or_default(),
        });
    }

    let pages: HashMap<String, String> =
        sources.iter().map(|s| (s.path.clone(), s.url.clone())).collect();
    if pages.len() != sources.len() {
        return Err("a Markdown file is listed twice in nav.toml".into());
    }
    let first = sources.first().ok_or("nav.toml lists no pages")?;
    if first.url != "index.html" {
        return Err("the first page in nav.toml must be the root README.md".into());
    }

    // First pass for the titles, which the navigation needs before any page is laid out.
    let mut rendered = Vec::new();
    for source in &mut sources {
        let dir = source.path.rsplit_once('/').map_or("", |(d, _)| d);
        let links = markdown::Links {
            pages: &pages,
            page_url: &source.url,
            source_dir: dir,
            repo_url: &repo_url,
            repo_root: root,
        };
        let page = markdown::render(&source.text, &links);
        for warning in &page.warnings {
            eprintln!("warning: {}: {warning}", source.path);
        }
        if source.nav_title.is_empty() {
            source.nav_title = page.title.clone().unwrap_or_else(|| source.path.clone());
        }
        rendered.push(page);
    }

    let titles: HashMap<&str, &str> =
        sources.iter().map(|s| (s.path.as_str(), s.nav_title.as_str())).collect();
    let nav_view: Vec<NavSection> = nav
        .sections
        .iter()
        .map(|section| NavSection {
            title: section.title.clone(),
            groups: section
                .groups
                .iter()
                .map(|group| NavGroup {
                    title: group.title.clone(),
                    items: group
                        .entries
                        .iter()
                        .map(|e| NavItem {
                            href: page_url(&e.path),
                            label: titles[e.path.as_str()].to_string(),
                        })
                        .collect(),
                })
                .collect(),
        })
        .collect();
    let browse_href = nav
        .entries()
        .map(|(_, e)| page_url(&e.path))
        .nth(2)
        .unwrap_or_else(|| "index.html".to_string());

    if out.exists() {
        fs::remove_dir_all(out)?;
    }
    fs::create_dir_all(out.join("logo"))?;

    for (source, page) in sources.iter().zip(rendered) {
        let mut body = page.html;
        for (i, snippet) in page.snippets.into_iter().enumerate() {
            let block = page::render_code(snippet.language, snippet.code).await;
            body = body.replace(&format!("<!--code:{i}-->"), &block);
        }
        let props = PageProps {
            nav: nav_view.clone(),
            url: source.url.clone(),
            section: source.section.clone(),
            body,
            repo_home: repo_home.clone(),
            landing: source.url == "index.html",
            browse_href: browse_href.clone(),
        };
        let title = if source.url == "index.html" {
            page::SITE_TITLE.to_string()
        } else {
            format!("{} \u{2014} {}", source.nav_title, page::SITE_TITLE)
        };
        let html = page::render_page(props, &base_href(&source.url, site_base.as_deref()), &title).await;
        let target = out.join(&source.url);
        fs::create_dir_all(target.parent().expect("pages sit under out"))?;
        fs::write(target, html)?;
    }

    fs::write(out.join("tokens.css"), TOKENS_CSS)?;
    fs::write(out.join("components.css"), COMPONENTS_CSS)?;
    fs::write(out.join("docs.css"), include_str!("../assets/docs.css"))?;
    fs::write(out.join("site-header.js"), include_str!("../assets/site-header.js"))?;
    fs::write(out.join("page.js"), DEMO_JS)?;
    fs::write(out.join("logo/logo-transparent.svg"), LOGO_SVG)?;
    // Keeps GitHub Pages from running the upload through Jekyll.
    fs::write(out.join(".nojekyll"), "")?;

    println!("wrote {} pages to {}", sources.len(), out.display());
    Ok(())
}
