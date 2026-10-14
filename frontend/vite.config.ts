import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const target = process.env.BACKEND_URL ?? 'http://localhost:8080';
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': target, '/actuator': target },
  },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'] },
});
