export default {
    server: {
        port: 8840,
        open: false,
        hmr: true,
        proxy: {
            // Proxy API requests to the backend server
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
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
