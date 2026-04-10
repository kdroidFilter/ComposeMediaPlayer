// Enable HTTPS for EME/DRM testing
config.devServer = config.devServer || {};
config.devServer.server = 'https';
config.devServer.host = '0.0.0.0';
