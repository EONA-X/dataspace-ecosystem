//! `nav.toml`: the curated navigation and the exclusion list.

use std::{error::Error, fs, path::Path};

use globset::{Glob, GlobSet, GlobSetBuilder};
use serde::Deserialize;

#[derive(Deserialize)]
struct NavFile {
    exclude: Vec<String>,
    #[serde(rename = "section")]
    sections: Vec<SectionCfg>,
}

#[derive(Deserialize)]
struct SectionCfg {
    title: String,
    #[serde(default)]
    pages: Vec<EntryCfg>,
    #[serde(default, rename = "group")]
    groups: Vec<GroupCfg>,
}

#[derive(Deserialize)]
struct GroupCfg {
    title: String,
    pages: Vec<EntryCfg>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum EntryCfg {
    Path(String),
    Titled { path: String, title: String },
}

/// A page listed in the navigation, with the Markdown file it comes from.
pub struct Entry {
    /// Repository-relative path of the Markdown file.
    pub path: String,
    /// Overrides the page's first heading when set.
    pub title: Option<String>,
}

pub struct Group {
    pub title: Option<String>,
    pub entries: Vec<Entry>,
}

pub struct Section {
    pub title: String,
    pub groups: Vec<Group>,
}

pub struct Nav {
    pub sections: Vec<Section>,
    exclude: GlobSet,
}

impl Nav {
    pub fn load(file: &Path) -> Result<Self, Box<dyn Error>> {
        let raw = fs::read_to_string(file).map_err(|e| format!("{}: {e}", file.display()))?;
        let cfg: NavFile = toml::from_str(&raw).map_err(|e| format!("{}: {e}", file.display()))?;

        let mut exclude = GlobSetBuilder::new();
        for pattern in &cfg.exclude {
            exclude.add(Glob::new(pattern)?);
        }

        let sections = cfg
            .sections
            .into_iter()
            .map(|section| {
                let mut groups = Vec::new();
                if !section.pages.is_empty() {
                    groups.push(Group { title: None, entries: entries(section.pages) });
                }
                groups.extend(section.groups.into_iter().map(|group| Group {
                    title: Some(group.title),
                    entries: entries(group.pages),
                }));
                Section { title: section.title, groups }
            })
            .collect();

        Ok(Self { sections, exclude: exclude.build()? })
    }

    pub fn is_excluded(&self, path: &str) -> bool {
        self.exclude.is_match(path)
    }

    /// Every entry, in navigation order, with the section and group it sits in.
    pub fn entries(&self) -> impl Iterator<Item = (&Section, &Entry)> {
        self.sections
            .iter()
            .flat_map(|s| s.groups.iter().flat_map(move |g| g.entries.iter().map(move |e| (s, e))))
    }
}

fn entries(cfg: Vec<EntryCfg>) -> Vec<Entry> {
    cfg.into_iter()
        .map(|entry| {
            let (path, title) = match entry {
                EntryCfg::Path(path) => (path, None),
                EntryCfg::Titled { path, title } => (path, Some(title)),
            };
            Entry { path: markdown_path(&path), title }
        })
        .collect()
}

/// A directory entry stands for its README.md.
fn markdown_path(path: &str) -> String {
    if path.to_ascii_lowercase().ends_with(".md") {
        path.to_string()
    } else {
        format!("{}/README.md", path.trim_end_matches('/'))
    }
}
