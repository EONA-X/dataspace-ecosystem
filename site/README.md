# Documentation site

A standalone Rust static-site generator that renders the repository's Markdown into a
documentation website, using [`eona-ui-toolkit`](https://github.com/EONA-X/eona-ui-toolkit)
components (Yew server-side rendering, no WASM). It is not part of the Gradle build.

## Build locally

```sh
cd site
cargo run --release            # writes dist/
cargo run -- --check           # verifies nav.toml covers every tracked .md
```

Open `dist/index.html` directly, or serve the directory with any static server.

`--out <dir>` changes the output directory (default `dist`).

## Navigation

`nav.toml` lists the pages, grouped by section, and an `exclude` list of globs.
`--check` fails when a tracked Markdown file (`git ls-files`) is neither listed nor
excluded, so a new `.md` must be added to one or the other. An entry is a Markdown file
or a directory standing for its `README.md`; a page's title is its first heading unless
the entry sets `title`.

Output paths mirror the sources: `dir/README.md` becomes `dir/index.html`, any other
`dir/name.md` becomes `dir/name.html`.

## Environment

| Variable | Default | Purpose |
| --- | --- | --- |
| `SITE_BASE` | unset | URL path the site is served under, e.g. `/dataspace-ecosystem/`. Unset, pages reference their assets relative to themselves. |
| `SITE_REPO_URL` | GitHub mirror, `main` branch | Blob URL prefix for links to repository files that are not pages (and their raw/tree variants). |
| `SITE_REPO_ROOT` | `..` | Repository checkout to read. |

## Publishing

- GitLab Pages: `.gitlab/ci/pages.yml`, job `pages` on the default branch.
- GitHub Pages: `.github/workflows/pages.yml` on push to `main`, with `SITE_BASE`
  taken from `actions/configure-pages`.

Both run `--check` first.
