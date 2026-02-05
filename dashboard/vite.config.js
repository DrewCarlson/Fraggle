export default {
    server: {
        port: 8840,
        open: false,
        hmr: true,
        proxy: {
            // Proxy API requests to the backend server
            '/api': {
                target: process.env.FRAGGLE_SERVER_URL,
                changeOrigin: true,
            },
            // Proxy WebSocket connections
            '/ws': {
                target: process.env.FRAGGLE_SERVER_URL,
                ws: true,
            },
        },
    },
    build: {
        rollupOptions: {
            output: {
                // Use IIFE format for simpler deployment
                format: 'iife',
                // Entry point name
                name: 'FraggleDashboard',
            },
        },
    },
}
