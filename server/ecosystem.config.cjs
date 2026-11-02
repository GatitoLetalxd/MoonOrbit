module.exports = {
  apps: [
    {
      name: 'moonorbit-api',
      script: 'dist/index.js',
      cwd: './',
      instances: 1, // Single instance to keep WebSocket session manager state in-memory
      autorestart: true,
      watch: false,
      max_memory_restart: '500M',
      env_production: {
        NODE_ENV: 'production',
        PORT: 3000,
        HOST: '0.0.0.0',
        TRUST_PROXY: 'true'
      }
    }
  ]
};
