// Vanilla-JS reproduction of eona-x.eu's real header scroll behavior
// (assets/frontend/js/components/header.js on the live site, a GSAP +
// ScrollTrigger class -- fetched and read directly). Same thresholds (200px
// scroll, desktop-only at >=1024px, reset on resize back to mobile), same
// effect (.is-scrolled class snaps the bar from its resting `top: 2rem` to
// `top: 0` and swaps the standalone logo/actions for the copies already
// sitting inside the pill), but plain CSS transitions instead of a GSAP
// timeline -- see SiteHeader's doc comment for why no GSAP dependency.
(function () {
  var header = document.querySelector(".site-header");
  if (!header) return;

  var desktopQuery = window.matchMedia("(min-width: 1024px)");
  var threshold = 200;
  var ticking = false;

  function update() {
    ticking = false;
    if (!desktopQuery.matches) {
      header.classList.remove("is-scrolled");
      return;
    }
    header.classList.toggle("is-scrolled", window.scrollY > threshold);
  }

  function requestTick() {
    if (!ticking) {
      ticking = true;
      requestAnimationFrame(update);
    }
  }

  update();
  window.addEventListener("scroll", requestTick, { passive: true });
  window.addEventListener("resize", requestTick);
  if (desktopQuery.addEventListener) {
    desktopQuery.addEventListener("change", update);
  } else if (desktopQuery.addListener) {
    desktopQuery.addListener(update); // Safari < 14
  }
})();
