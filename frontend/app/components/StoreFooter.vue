<template>
  <footer class="store-footer">
    <div class="store-container store-footer-grid">
      <div class="store-footer-brand">
        <NuxtLink class="store-footer-logo" to="/">PELISSA<i>°</i></NuxtLink>
        <p>Modern intimates for every version of you.</p>
        <div class="store-social-links">
          <a href="#" aria-label="Instagram"><UIcon name="i-lucide-instagram" /></a>
          <a href="#" aria-label="TikTok"><UIcon name="i-lucide-music-2" /></a>
          <a href="#" aria-label="Pinterest"><UIcon name="i-lucide-pin" /></a>
        </div>
      </div>

      <div class="store-footer-column">
        <strong>Shop</strong>
        <NuxtLink to="/collections/new">New in</NuxtLink>
        <NuxtLink to="/collections/lounge">Lounge lingerie</NuxtLink>
        <NuxtLink to="/collections/swim">Swim lingerie</NuxtLink>
        <NuxtLink to="/collections/intimate">Intimates</NuxtLink>
        <NuxtLink to="/collections/sale">Sale</NuxtLink>
      </div>

      <div class="store-footer-column">
        <strong>Explore</strong>
        <NuxtLink to="/collections/shop">The full collection</NuxtLink>
        <NuxtLink to="/search">Search</NuxtLink>
        <NuxtLink to="/product/1">Fit &amp; details</NuxtLink>
        <NuxtLink to="/login">My account</NuxtLink>
      </div>

      <div class="store-footer-column">
        <strong>Help</strong>
        <a href="#">Shipping &amp; returns</a>
        <a href="#">Track an order</a>
        <a href="#">Contact us</a>
        <a href="#">FAQs</a>
      </div>
    </div>

    <div class="store-container store-footer-bottom">
      <span>© 2026 Pelissa. All rights reserved.</span>
      <div>
        <a href="#">Privacy</a>
        <a href="#">Terms</a>
        <button type="button">United States / USD <UIcon name="i-lucide-chevron-down" /></button>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.store-footer {
  padding-top: 74px;
  color: #fbf7f5;
  background: var(--store-ink);
}

.store-footer-grid {
  display: grid;
  grid-template-columns: 2fr repeat(3, 1fr);
  gap: 48px;
  padding-bottom: 68px;
}

.store-footer-brand {
  max-width: 270px;
}

.store-footer-logo {
  display: inline-flex;
  align-items: flex-start;
  color: #fff;
  font-size: 27px;
  font-weight: 700;
  letter-spacing: .09em;
  line-height: 1;
  text-decoration: none;
}

.store-footer-logo i {
  margin: -3px 0 0 3px;
  color: var(--store-blush);
  font-family: Georgia, serif;
  font-size: 20px;
  font-style: normal;
}

.store-footer-brand p {
  margin: 18px 0 24px;
  color: #bbaeb2;
  font-size: 13px;
  line-height: 1.6;
}

.store-social-links {
  display: flex;
  gap: 9px;
}

.store-social-links a {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid #5e5157;
  border-radius: 50%;
  color: #fff;
}

.store-social-links .iconify {
  width: 14px;
  height: 14px;
}

.store-footer-column {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 11px;
}

.store-footer-column strong {
  margin-bottom: 7px;
  color: #fff;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .1em;
  text-transform: uppercase;
}

.store-footer-column a {
  color: #c3b8bd;
  font-size: 12px;
  text-decoration: none;
}

.store-footer-column a:hover {
  color: #fff;
}

.store-footer-bottom {
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-top: 1px solid #4b3d44;
  color: #b7aab0;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .035em;
  text-transform: uppercase;
}

.store-footer-bottom div {
  display: flex;
  align-items: center;
  gap: 22px;
}

.store-footer-bottom a {
  color: inherit;
  text-decoration: none;
}

.store-footer-bottom button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: 0;
  color: inherit;
  background: none;
  cursor: pointer;
  font-size: inherit;
  text-transform: inherit;
}

.store-footer-bottom .iconify {
  width: 12px;
  height: 12px;
}

@media (max-width: 820px) {
  .store-footer {
    padding-top: 58px;
  }

  .store-footer-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 42px 24px;
    padding-bottom: 48px;
  }

  .store-footer-brand {
    grid-column: 1 / -1;
  }

  .store-footer-bottom {
    min-height: auto;
    align-items: flex-start;
    flex-direction: column;
    padding-top: 18px;
    padding-bottom: 22px;
  }

  .store-footer-bottom div {
    flex-wrap: wrap;
    gap: 10px 17px;
  }
}
</style>
