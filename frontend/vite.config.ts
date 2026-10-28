import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // KBMD_API points the dev server at another backend, e.g. the one the end-to-end tests start
    proxy: { '/api': process.env.KBMD_API ?? 'http://127.0.0.1:8787' },
  },
  build: {
    // picked up by Spring Boot as static resources and packaged into the jar
    outDir: '../target/classes/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 4000,
  },
});
