/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      // Same-origin /api calls in dev: no CORS round-trips, and no backend URL baked into the bundle.
      proxy: {
        '/api': env.API_PROXY_TARGET || 'http://localhost:8080',
      },
    },
    test: {
      environment: 'node',
    },
  };
});
