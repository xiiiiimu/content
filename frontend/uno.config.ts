import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  transformerDirectives,
  transformerVariantGroup,
} from 'unocss'

/** Palette inspired by Elk (light/dark content surfaces). */
export default defineConfig({
  shortcuts: {
    'border-base': 'border-$c-border',
    'bg-base': 'bg-$c-bg',
    'bg-active': 'bg-$c-bg-active',
    'text-primary': 'text-$c-primary',
    'text-secondary': 'text-$c-secondary',
    'btn-text': 'px3 py1.5 rounded-lg text-primary hover:bg-active transition',
    'btn-solid': 'px3 py1.5 rounded-lg bg-$c-primary text-white hover:opacity-90 transition disabled:opacity-50',
    'card-row': 'block p4 border-b border-base hover:bg-active focus:outline-none focus-visible:ring-2 focus-visible:ring-$c-primary',
  },
  theme: {
    colors: {
      // CSS variables set in main.css
    },
  },
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons({ scale: 1.2, extraProperties: { display: 'inline-block', 'vertical-align': 'middle' } }),
    presetTypography(),
  ],
  transformers: [transformerDirectives(), transformerVariantGroup()],
})
