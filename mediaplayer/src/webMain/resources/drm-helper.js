/**
 * DRM Helper Functions for ComposeMediaPlayer
 * These functions bridge Kotlin/WASM with EME browser APIs
 */

// Global namespace for DRM functions
window.DrmHelper = window.DrmHelper || {};

/**
 * Check if EME is supported
 */
window.DrmHelper.isSupported = function() {
    return typeof navigator !== 'undefined' && 
           typeof navigator.requestMediaKeySystemAccess === 'function';
};

/**
 * Request MediaKeySystemAccess
 * @param {string} keySystem - The key system identifier (e.g., "com.widevine.alpha")
 * @param {string} configJson - JSON string of MediaKeySystemConfiguration array
 * @returns {Promise<MediaKeySystemAccess|null>}
 */
window.DrmHelper.requestAccess = function(keySystem, configJson) {
    if (!this.isSupported()) {
        return Promise.resolve(null);
    }
    
    try {
        var config = JSON.parse(configJson);
        return navigator.requestMediaKeySystemAccess(keySystem, config);
    } catch(e) {
        console.error('DrmHelper.requestAccess failed:', e);
        return Promise.reject(e);
    }
};

/**
 * Create MediaKeys from MediaKeySystemAccess
 * @param {MediaKeySystemAccess} access
 * @returns {Promise<MediaKeys>}
 */
window.DrmHelper.createMediaKeys = function(access) {
    return access.createMediaKeys();
};

/**
 * Set MediaKeys on video element
 * @param {HTMLVideoElement} video
 * @param {MediaKeys} mediaKeys
 * @returns {Promise}
 */
window.DrmHelper.setMediaKeys = function(video, mediaKeys) {
    return video.setMediaKeys(mediaKeys);
};

/**
 * Create a media key session
 * @param {MediaKeys} mediaKeys
 * @param {string} sessionType - "temporary" or "persistent-license"
 * @returns {MediaKeySession}
 */
window.DrmHelper.createSession = function(mediaKeys, sessionType) {
    return mediaKeys.createSession(sessionType || 'temporary');
};

/**
 * Generate a license request
 * @param {MediaKeySession} session
 * @param {string} initDataType
 * @param {ArrayBuffer} initData
 * @returns {Promise}
 */
window.DrmHelper.generateRequest = function(session, initDataType, initData) {
    return session.generateRequest(initDataType, initData);
};

/**
 * Update session with license
 * @param {MediaKeySession} session
 * @param {ArrayBuffer} license
 * @returns {Promise}
 */
window.DrmHelper.updateSession = function(session, license) {
    return session.update(license);
};

/**
 * Close session
 * @param {MediaKeySession} session
 * @returns {Promise}
 */
window.DrmHelper.closeSession = function(session) {
    if (session && typeof session.close === 'function') {
        return session.close();
    }
    return Promise.resolve();
};

/**
 * Fetch license from server
 * @param {string} licenseUrl
 * @param {ArrayBuffer} message - The license request message
 * @param {Object} headers - Additional headers for the request
 * @returns {Promise<ArrayBuffer>}
 */
window.DrmHelper.fetchLicense = function(licenseUrl, message, headers) {
    var fetchOptions = {
        method: 'POST',
        body: message,
        headers: Object.assign({ 'Content-Type': 'application/octet-stream' }, headers || {})
    };
    
    return fetch(licenseUrl, fetchOptions)
        .then(function(response) {
            if (!response.ok) {
                throw new Error('License request failed: ' + response.status + ' ' + response.statusText);
            }
            return response.arrayBuffer();
        });
};

/**
 * Setup DRM for a video element with full workflow
 * @param {HTMLVideoElement} video
 * @param {string} keySystem - e.g., "com.widevine.alpha"
 * @param {string} licenseUrl
 * @param {Object} licenseHeaders
 * @returns {Promise<{mediaKeys: MediaKeys, cleanup: Function}>}
 */
window.DrmHelper.setup = function(video, keySystem, licenseUrl, licenseHeaders) {
    var self = this;
    var mediaKeys = null;
    var sessions = [];
    
    var config = [{
        initDataTypes: ['cenc', 'keyids', 'webm'],
        videoCapabilities: [
            { contentType: 'video/mp4; codecs="avc1.42E01E"' },
            { contentType: 'video/mp4; codecs="avc1.4D401E"' },
            { contentType: 'video/mp4; codecs="avc1.64001E"' },
            { contentType: 'video/webm; codecs="vp8"' },
            { contentType: 'video/webm; codecs="vp9"' }
        ],
        audioCapabilities: [
            { contentType: 'audio/mp4; codecs="mp4a.40.2"' },
            { contentType: 'audio/webm; codecs="opus"' },
            { contentType: 'audio/webm; codecs="vorbis"' }
        ],
        distinctiveIdentifier: 'optional',
        persistentState: 'optional',
        sessionTypes: ['temporary']
    }];
    
    function handleEncrypted(event) {
        console.log('[DRM] Encrypted event received:', event.initDataType);
        
        if (!mediaKeys) {
            console.error('[DRM] MediaKeys not available');
            return;
        }
        
        var session = mediaKeys.createSession('temporary');
        sessions.push(session);
        
        session.addEventListener('message', function(messageEvent) {
            console.log('[DRM] Session message:', messageEvent.messageType);
            
            self.fetchLicense(licenseUrl, messageEvent.message, licenseHeaders)
                .then(function(license) {
                    console.log('[DRM] License received, size:', license.byteLength);
                    return session.update(license);
                })
                .then(function() {
                    console.log('[DRM] License applied successfully');
                })
                .catch(function(error) {
                    console.error('[DRM] License acquisition failed:', error);
                });
        });
        
        session.addEventListener('keystatuseschange', function() {
            console.log('[DRM] Key status changed');
            session.keyStatuses.forEach(function(status, keyId) {
                console.log('[DRM] Key status:', status);
            });
        });
        
        session.generateRequest(event.initDataType, event.initData)
            .then(function() {
                console.log('[DRM] License request generated');
            })
            .catch(function(error) {
                console.error('[DRM] generateRequest failed:', error);
            });
    }
    
    return navigator.requestMediaKeySystemAccess(keySystem, config)
        .then(function(access) {
            console.log('[DRM] Got MediaKeySystemAccess for:', keySystem);
            return access.createMediaKeys();
        })
        .then(function(keys) {
            console.log('[DRM] MediaKeys created');
            mediaKeys = keys;
            return video.setMediaKeys(keys);
        })
        .then(function() {
            console.log('[DRM] MediaKeys attached to video');
            video.addEventListener('encrypted', handleEncrypted);
            
            return {
                mediaKeys: mediaKeys,
                cleanup: function() {
                    video.removeEventListener('encrypted', handleEncrypted);
                    sessions.forEach(function(s) {
                        s.close().catch(function(e) {
                            console.log('[DRM] Session close error:', e);
                        });
                    });
                    sessions = [];
                }
            };
        });
};

/**
 * Get key system string for DRM type
 * @param {string} drmType - "WIDEVINE", "PLAYREADY", or "CLEARKEY"
 * @returns {string}
 */
window.DrmHelper.getKeySystem = function(drmType) {
    switch((drmType || '').toUpperCase()) {
        case 'WIDEVINE': return 'com.widevine.alpha';
        case 'PLAYREADY': return 'com.microsoft.playready';
        case 'CLEARKEY': return 'org.w3.clearkey';
        default: return 'com.widevine.alpha';
    }
};

/**
 * Setup DASH playback with DRM using dash.js
 * Matches the reference implementation from dashif.org
 * @param {HTMLVideoElement} video
 * @param {string} url - The DASH manifest URL (.mpd)
 * @param {string} drmType - "WIDEVINE", "PLAYREADY", or "CLEARKEY"
 * @param {string} licenseUrl
 * @param {Object} licenseHeaders
 * @returns {{player: Object, cleanup: Function}}
 */
window.DrmHelper.setupDash = function(video, url, drmType, licenseUrl, licenseHeaders) {
    // Check if dash.js is available
    if (typeof dashjs === 'undefined') {
        console.error('[DRM] dash.js not loaded! Include it before drm-helper.js');
        return null;
    }
    
    // Convert JsString to native string if needed
    var urlStr = (typeof url === 'object' && url.toString) ? url.toString() : String(url);
    var licenseUrlStr = (typeof licenseUrl === 'object' && licenseUrl.toString) ? licenseUrl.toString() : String(licenseUrl);
    var drmTypeStr = (typeof drmType === 'object' && drmType.toString) ? drmType.toString() : String(drmType);
    
    console.log('[DRM] setupDash called:', {
        url: urlStr,
        drmType: drmTypeStr,
        licenseUrl: licenseUrlStr,
        hasHeaders: licenseHeaders ? Object.keys(licenseHeaders).length : 0
    });
    
    // Build protection data exactly like the reference implementation
    var keySystem = this.getKeySystem(drmTypeStr);
    var protData = {};
    
    protData[keySystem] = {
        "serverURL": licenseUrlStr,
        "priority": 0
    };
    
    // Add headers if provided
    if (licenseHeaders && typeof licenseHeaders === 'object') {
        var headerKeys = Object.keys(licenseHeaders);
        if (headerKeys.length > 0) {
            protData[keySystem].httpRequestHeaders = {};
            for (var i = 0; i < headerKeys.length; i++) {
                var hKey = headerKeys[i];
                var hVal = licenseHeaders[hKey];
                // Convert to native string
                protData[keySystem].httpRequestHeaders[hKey] = 
                    (typeof hVal === 'object' && hVal.toString) ? hVal.toString() : String(hVal);
            }
        }
    }
    
    console.log('[DRM] Protection data:', JSON.stringify(protData, null, 2));
    
    // Create player
    var player = dashjs.MediaPlayer().create();
    
    // Initialize and set protection (same order as reference)
    player.initialize(video, urlStr, true);
    player.setProtectionData(protData);
    
    // Log events for debugging
    player.on(dashjs.MediaPlayer.events.ERROR, function(e) {
        console.error('[DRM] dash.js error:', e);
    });
    
    player.on(dashjs.MediaPlayer.events.PROTECTION_CREATED, function(e) {
        console.log('[DRM] Protection created');
    });
    
    player.on(dashjs.MediaPlayer.events.KEY_SYSTEM_SELECTED, function(e) {
        console.log('[DRM] Key system selected:', e.data ? e.data.keySystem.systemString : 'unknown');
    });
    
    player.on(dashjs.MediaPlayer.events.LICENSE_REQUEST_COMPLETE, function(e) {
        if (e.error) {
            console.error('[DRM] License request failed:', e.error);
        } else {
            console.log('[DRM] License request complete');
        }
    });
    
    player.on(dashjs.MediaPlayer.events.KEY_SESSION_CREATED, function(e) {
        console.log('[DRM] Key session created');
    });
    
    player.on(dashjs.MediaPlayer.events.KEY_STATUSES_CHANGED, function(e) {
        console.log('[DRM] Key statuses changed');
    });
    
    player.on(dashjs.MediaPlayer.events.PLAYBACK_STARTED, function(e) {
        console.log('[DRM] Playback started!');
    });
    
    player.on(dashjs.MediaPlayer.events.CAN_PLAY, function(e) {
        console.log('[DRM] Can play');
    });
    
    return {
        player: player,
        cleanup: function() {
            console.log('[DRM] Cleaning up dash.js player');
            try {
                player.reset();
            } catch(e) {
                console.log('[DRM] Player reset error:', e);
            }
        }
    };
};

/**
 * Check if URL is a DASH manifest
 * @param {string} url
 * @returns {boolean}
 */
window.DrmHelper.isDashUrl = function(url) {
    return url && (url.toLowerCase().endsWith('.mpd') || url.includes('.mpd?'));
};

/**
 * Parse JSON string to object (helper for WASM which can't call JSON.parse directly)
 */
window.DrmHelper.parseJson = function(jsonString) {
    try {
        var str = (typeof jsonString === 'object' && jsonString.toString) ? jsonString.toString() : String(jsonString);
        return JSON.parse(str);
    } catch(e) {
        console.error('[DRM] JSON parse error:', e);
        return null;
    }
};

console.log('[DRM] DrmHelper loaded (with dash.js support)');
