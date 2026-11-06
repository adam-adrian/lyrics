/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
// Run with Bun or Node 22+. No dependencies; never prints tokens or lyrics.
const args = process.argv.slice(2);
const title = args[0] ?? 'Levitating';
const artist = args[1] ?? 'Dua Lipa';
const duration = Number(args[2] ?? -1);
if (!title.trim() || !artist.trim() || !Number.isFinite(duration) || (duration !== -1 && duration <= 0)) {
    console.error('Usage: bun probe-search.mjs [title] [artist] [durationSeconds|-1]');
    process.exit(1);
}

const base = 'https://apic-appmobile.musixmatch.com/ws/1.1/';
const appId = 'mac-ios-v2.0';
const headers = {
    Accept: 'application/json',
    'Accept-Language': 'en-US,en;q=0.9',
    'X-Cookie': 'x-mxm-token-guid=',
    'x-mxm-app-version': '10.1.1',
    'X-User-Agent': 'Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0',
};
const controller = new AbortController();
const started = performance.now();
const elapsed = () => Math.round(performance.now() - started);
const timer = setTimeout(() => controller.abort(), 8000);
const report = {
    title, artist, durationSeconds: duration, budgetMs: 8000, concurrency: 2,
    tokenRequests: 0, stages: [], candidates: [],
};

async function request(endpoint, params) {
    const start = performance.now();
    const url = new URL(endpoint, base);
    url.search = new URLSearchParams({ app_id: appId, ...params }).toString();
    const response = await fetch(url, { headers, signal: controller.signal });
    // On HTTP failure, do not parse or log an arbitrary error body.
    if (!response.ok) {
        await response.body?.cancel();
        throw new Error(`${endpoint}: HTTP ${response.status}`);
    }
    let data;
    try {
        data = await response.json();
    } catch {
        throw new Error(`${endpoint}: invalid JSON`);
    }
    const status = data?.message?.header?.status_code;
    report.stages.push({ endpoint, http: response.status, status, elapsedMs: Math.round(performance.now() - start) });
    if (status !== 200) throw new Error(`${endpoint}: payload status ${Number.isInteger(status) ? status : 'missing'}`);
    return data.message.body;
}

function decodedArray(value) {
    try {
        const parsed = JSON.parse(value);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

async function main() {
    report.tokenRequests++;
    const tokenBody = await request('token.get', {});
    const token = tokenBody?.user_token;
    if (typeof token !== 'string' || !token.trim() || /^0+$/.test(token) || token.startsWith('UpgradeOnly')) {
        throw new Error('token.get: missing or invalid token');
    }
    // The same token is reused for search and every candidate. No token refresh.
    const search = await request('track.search', {
        usertoken: token, q_track: title, q_artist: artist, q_artists: artist,
        page_size: '5', f_has_lyrics: '1',
    });
    if (!Array.isArray(search?.track_list)) throw new Error('track.search: missing track_list');
    const tracks = search.track_list.map(item => item.track);
    report.returnedCandidates = tracks.length;
    const eligible = tracks.filter(track =>
        track?.track_id > 0 && track.instrumental !== 1 &&
        (duration === -1 || !(track.track_length > 0) || Math.abs(track.track_length - duration) <= 8),
    ).slice(0, 5);
    report.eligibleCandidates = eligible.length;
    let next = 0;
    async function worker() {
        while (!controller.signal.aborted) {
            const track = eligible[next++];
            if (!track) return;
            const result = { trackId: track.track_id, title: track.track_name, artist: track.artist_name, durationSeconds: track.track_length };
            report.candidates.push(result);
            try {
                const body = await request('macro.subtitles.get', {
                    usertoken: token, track_id: String(track.track_id), format: 'json',
                    namespace: 'lyrics_richsynched', subtitle_format: 'mxm',
                    optional_calls: 'track.richsync', richsync_compact_type: 'words',
                    part: 'track_lyrics_translation_status,track_structure,track_performer_tagging',
                });
                const calls = body?.macro_calls;
                const message = key => calls?.[key]?.message;
                const matcher = message('matcher.track.get');
                result.matchedId = matcher?.body?.track?.track_id;
                if (matcher?.header?.status_code !== 200 || result.matchedId !== track.track_id) {
                    throw new Error('macro: matcher status or track_id mismatch');
                }
                const plain = message('track.lyrics.get');
                if (plain?.body?.lyrics?.restricted === 1) throw new Error('macro: lyrics restricted');
                const rich = message('track.richsync.get');
                const subtitle = message('track.subtitles.get');
                result.richsyncStatus = rich?.header?.status_code ?? null;
                result.subtitleStatus = subtitle?.header?.status_code ?? null;
                result.richsyncLines = rich?.header?.status_code === 200
                    ? decodedArray(rich.body?.richsync?.richsync_body).filter(line =>
                        line.te - line.ts >= 0.002 && (line.l?.some(word => word.c?.trim()) || line.x?.trim()),
                    ).length : 0;
                result.subtitleLines = subtitle?.header?.status_code === 200
                    ? decodedArray(subtitle.body?.subtitle_list?.[0]?.subtitle?.subtitle_body).length : 0;
                result.formatAvailable = result.richsyncLines ? 'richsync'
                    : result.subtitleLines ? 'subtitle'
                    : plain?.header?.status_code === 200 && plain.body?.lyrics?.lyrics_body?.trim() ? 'plain' : null;
                if (!result.formatAvailable) throw new Error('macro: no usable lyrics format');
                result.completedAtMs = elapsed();
            } catch (error) {
                // Only locally constructed messages are exposed; fetch errors may contain URLs.
                result.error = controller.signal.aborted ? 'budget exceeded' :
                    /^(macro|macro\.subtitles\.get):/.test(error.message) ? error.message : 'request failed';
            }
        }
    }
    await Promise.all([worker(), worker()]);
    report.notStartedCandidates = eligible.length - report.candidates.length;
}

try {
    await main();
} catch (error) {
    report.error = controller.signal.aborted ? 'budget exceeded' :
        /^(token\.get|track\.search):/.test(error.message) ? error.message : 'request failed';
} finally {
    clearTimeout(timer);
    report.elapsedMs = elapsed();
    report.budgetExceeded = controller.signal.aborted;
    report.successfulCandidates = report.candidates.filter(item => item.formatAvailable && !item.error).length;
    report.allEligibleSucceeded = !report.error && !report.budgetExceeded && report.eligibleCandidates > 0 &&
        report.successfulCandidates === report.eligibleCandidates;
    console.log(JSON.stringify(report, null, 2));
    if (!report.allEligibleSucceeded) process.exitCode = 1;
}
