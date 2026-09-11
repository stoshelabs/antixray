import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import VersionBanner from './VersionBanner.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  // layout-top rather than doc-top: an archived version has to say so on the
  // landing page as well, and doc-top only renders inside a document.
  Layout: () => h(DefaultTheme.Layout, null, { 'layout-top': () => h(VersionBanner) }),
}
