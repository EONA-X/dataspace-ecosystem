//! The page layout, built from toolkit components.

use eona_ui_toolkit::{
    Button, ButtonVariant, CodeBlock, NavLink, SimpleFooter, SiteHeader,
    SiteNavLink,
};
use yew::prelude::*;
use yew::ServerRenderer;

pub const SITE_TITLE: &str = "Dataspace Ecosystem \u{2014} Documentation";

/// The HTML document pages are rendered into. Assets are linked relative to the
/// `<base href>`, which also makes the toolkit header's own `index.html` and logo
/// links resolve from any depth.
const SHELL: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<base href="__BASE__">
<title>__TITLE__</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Poppins:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap" rel="stylesheet">
<link rel="stylesheet" href="tokens.css">
<link rel="stylesheet" href="components.css">
<link rel="stylesheet" href="docs.css">
<link rel="icon" type="image/svg+xml" href="logo/logo-transparent.svg">
</head>
<body>
__APP__
<script src="site-header.js"></script>
<script src="page.js"></script>
</body>
</html>
"#;

#[derive(Clone, PartialEq)]
pub struct NavItem {
    pub href: String,
    pub label: String,
}

#[derive(Clone, PartialEq)]
pub struct NavGroup {
    pub title: Option<String>,
    pub items: Vec<NavItem>,
}

#[derive(Clone, PartialEq)]
pub struct NavSection {
    pub title: String,
    pub groups: Vec<NavGroup>,
}

#[derive(Properties, Clone, PartialEq)]
pub struct PageProps {
    pub nav: Vec<NavSection>,
    /// URL of this page, relative to the site root.
    pub url: String,
    pub section: String,
    /// Rendered Markdown, trusted: it comes from this repository.
    pub body: String,
    /// Link to the repository's home page.
    pub repo_home: String,
    pub landing: bool,
    /// Where the landing page's call to action leads.
    pub browse_href: String,
}

#[function_component(Page)]
pub fn page(props: &PageProps) -> Html {
    let header_links = vec![
        SiteNavLink { href: "index.html".into(), label: "Documentation".into(), current: true },
        SiteNavLink { href: props.repo_home.clone().into(), label: "Repository".into(), current: false },
    ];

    html! {
        <>
            <SiteHeader links={header_links} />
            if props.landing {
                // The toolkit's Hero bakes in a "The EONA-X …" title, so its
                // compact markup is rendered here with the landing page's own title.
                <header class="libhero">
                    <div class="gold-stripe"></div>
                    <div class="wrap">
                        <span class="hero-eyebrow"><span class="sw"></span>{ " Documentation" }</span>
                        <h1>{ "Technical documentation of the " }<em>{ "EONA-X" }</em>{ " Dataspace Ecosystem" }</h1>
                        <p class="lede">{ "EDC-based components for the EONA-X dataspace: modules, migrations and contribution guides." }</p>
                        <div class="hero-actions">
                            <Button label="Browse the modules" variant={ButtonVariant::Primary}
                                href={Some(AttrValue::from(props.browse_href.clone()))} />
                        </div>
                    </div>
                </header>
            }
            <div class="wrap docs-layout">
                <nav class="docs-sidebar" aria-label="Documentation">
                    { for props.nav.iter().map(|section| html! {
                        <div class="docs-nav-section">
                            <h2 class="docs-nav-title">{ &section.title }</h2>
                            { for section.groups.iter().map(|group| html! {
                                <>
                                    if let Some(title) = &group.title {
                                        <h3 class="docs-nav-group">{ title }</h3>
                                    }
                                    <ul>
                                        { for group.items.iter().map(|item| {
                                            let current = item.href == props.url;
                                            html! {
                                                <li>
                                                    <a href={item.href.clone()}
                                                        class={classes!(current.then_some("is-current"))}
                                                        aria-current={current.then_some("page")}>
                                                        { &item.label }
                                                    </a>
                                                </li>
                                            }
                                        }) }
                                    </ul>
                                </>
                            }) }
                        </div>
                    }) }
                </nav>
                <main class="docs-content">
                    <p class="docs-crumb">{ SITE_TITLE }{ " / " }{ &props.section }</p>
                    <article class="docs-body">
                        { Html::from_html_unchecked(AttrValue::from(props.body.clone())) }
                    </article>
                </main>
            </div>
            <SimpleFooter
                text="Dataspace Ecosystem documentation, generated from the repository's Markdown."
                link={NavLink::new("index.html", "Documentation home")}
            />
        </>
    }
}

pub async fn render_page(props: PageProps, base: &str, title: &str) -> String {
    let body = ServerRenderer::<Page>::with_props(move || props).render().await;
    SHELL
        .replace("__BASE__", &escape(base))
        .replace("__TITLE__", &escape(title))
        .replace("__APP__", &body)
}

/// A fenced block through the toolkit's `CodeBlock`, as an HTML fragment.
pub async fn render_code(language: String, code: String) -> String {
    #[derive(Properties, Clone, PartialEq)]
    struct Props {
        language: String,
        code: String,
    }

    #[function_component(Snippet)]
    fn snippet(props: &Props) -> Html {
        html! { <CodeBlock language={props.language.clone()} code={props.code.clone()} /> }
    }

    ServerRenderer::<Snippet>::with_props(move || Props { language, code }).render().await
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}
