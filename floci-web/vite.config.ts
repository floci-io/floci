import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // Shared proxy for every AWS service Floci exposes on port 4566.
      // Each service-specific SDK client points at this alias; Floci routes
      // by Action / X-Amz-Target / path, not by URL prefix.
      '/_aws': {
        target: 'http://localhost:4566',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/_aws/, ''),
      },
    },
  },
});
