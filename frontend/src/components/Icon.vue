<script setup>
defineProps({ name: String, size: { default: 20 } })
const paths = {
 book: 'M4 3h6a3 3 0 0 1 3 3v15a4 4 0 0 0-4-3H3V3h1Zm9 3a3 3 0 0 1 3-3h5v15h-5a3 3 0 0 0-3 3',
 plus: 'M12 5v14M5 12h14', chat: 'M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z',
 arrow: 'M12 19V5m-6 6 6-6 6 6', chevron: 'm9 5 7 7-7 7', search: 'm21 21-5-5M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0',
 file: 'M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 12h8M8 16h6', chart: 'M3 3v18h18M7 15v-4m5 4V7m5 8V4',
 feedback: 'M8 10h8M8 14h5M21 3H3v15h6l3 3 3-3h6V3Z', logout: 'M9 3H3v18h6m6-14 5 5-5 5m-8-5h13',
 check: 'm5 12 4 4L19 6', close: 'm6 6 12 12M6 18 18 6', upload: 'M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5',
 refresh: 'M20 7v5h-5M4 17v-5h5m10-5a8 8 0 0 0-13-2l-2 3m16 8-2 3a8 8 0 0 1-13-2',
 shield: 'm12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6l9-4Zm-4 10 3 3 5-6',
 spark: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z',
 like: 'M7 10v11H3V10h4Zm0 0 6-8c2 0 2 2 1 6h6v4l-2 9H7',
 dislike: 'M7 14V3H3v11h4Zm0 0 6 8c2 0 2-2 1-6h6v-4l-2-9H7',
 lock: 'M5 10h14v11H5V10Zm3 0V6a4 4 0 0 1 8 0v4', menu: 'M4 6h16M4 12h16M4 18h16',
 clock: 'M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0'
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.file" /></svg></template>
