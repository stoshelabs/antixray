<script setup>
import { useData } from 'vitepress'

// Built by versions.ts and carried here through themeConfig, so nothing in this
// component has to read process.env — it renders in the browser too.
const { theme } = useData()
</script>

<template>
  <div v-if="theme.versionBanner" class="version-banner">
    You are reading the documentation for <strong>{{ theme.versionBanner.label }}</strong>. The
    latest release is
    <a :href="theme.versionBanner.latestLink">{{ theme.versionBanner.latestLabel }}</a>.
  </div>
</template>

<style scoped>
/*
 * The height here is the one config.mts writes into --vp-layout-top-height: the
 * layout-top slot sits above a fixed navbar, so the layout only makes room for a
 * banner whose height it was told. Keep the two in step.
 *
 * The brand teal → violet, a shade darker than the buttons so white text stays
 * readable across the whole gradient.
 */
.version-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 40px;
  padding: 0 24px;
  font-size: 14px;
  line-height: 1.4;
  text-align: center;
  color: #fff;
  background: linear-gradient(90deg, #0b7f76, #5a24c9);
}

.version-banner a {
  color: #fff;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
