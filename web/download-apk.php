<?php
declare(strict_types=1);

/*
 * Resolver unduhan Android BeatPlay.
 *
 * MediaFire gratis tidak menyediakan direct-download permanen. Halaman ini
 * mengambil link CDN sementara dari halaman file resmi MediaFire, lalu
 * mengalihkan browser ke link tersebut. Kalau pengambilan gagal, browser
 * diarahkan ke halaman file MediaFire sebagai fallback.
 */

const MEDIAFIRE_FILE_PAGE = 'https://www.mediafire.com/file/qh977ew8l7rqk0y/BeatPlay-1.2.1-android.apk/file';

function beatplay_fetch(string $url): ?string
{
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        if ($ch !== false) {
            curl_setopt_array($ch, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_FOLLOWLOCATION => true,
                CURLOPT_MAXREDIRS => 5,
                CURLOPT_CONNECTTIMEOUT => 10,
                CURLOPT_TIMEOUT => 20,
                CURLOPT_ENCODING => '',
                CURLOPT_USERAGENT => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
                CURLOPT_HTTPHEADER => ['Accept-Language: en-US,en;q=0.9'],
                CURLOPT_SSL_VERIFYPEER => true,
                CURLOPT_SSL_VERIFYHOST => 2,
            ]);
            $html = curl_exec($ch);
            curl_close($ch);
            if (is_string($html) && $html !== '') {
                return $html;
            }
        }
    }

    if (ini_get('allow_url_fopen')) {
        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'timeout' => 20,
                'follow_location' => 1,
                'max_redirects' => 5,
                'header' => "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36\r\nAccept-Language: en-US,en;q=0.9\r\n",
            ],
            'ssl' => [
                'verify_peer' => true,
                'verify_peer_name' => true,
            ],
        ]);
        $html = @file_get_contents($url, false, $context);
        if (is_string($html) && $html !== '') {
            return $html;
        }
    }

    return null;
}

function beatplay_mediafire_direct(string $html): ?string
{
    $patterns = [
        '~<a\b[^>]*\baria-label=["\']Download file["\'][^>]*\bhref=["\']([^"\']+)["\']~i',
        '~<a\b[^>]*\bhref=["\']([^"\']+)["\'][^>]*\baria-label=["\']Download file["\']~i',
        '~<a\b[^>]*\bid=["\']downloadButton["\'][^>]*\bhref=["\']([^"\']+)["\']~i',
    ];

    foreach ($patterns as $pattern) {
        if (!preg_match($pattern, $html, $match)) {
            continue;
        }
        $url = html_entity_decode($match[1], ENT_QUOTES | ENT_HTML5, 'UTF-8');
        if (preg_match('~^https://download\d*\.mediafire\.com/~i', $url)) {
            return $url;
        }
    }

    if (preg_match('~data-scrambled-url=["\']([^"\']+)["\']~i', $html, $match)) {
        $encoded = html_entity_decode($match[1], ENT_QUOTES | ENT_HTML5, 'UTF-8');
        $encoded = strtr($encoded, '-_', '+/');
        $encoded .= str_repeat('=', (4 - strlen($encoded) % 4) % 4);
        $decoded = base64_decode($encoded, true);
        if (is_string($decoded) && preg_match('~^https://download\d*\.mediafire\.com/~i', $decoded)) {
            return $decoded;
        }
    }

    return null;
}

$html = beatplay_fetch(MEDIAFIRE_FILE_PAGE);
$direct = $html !== null ? beatplay_mediafire_direct($html) : null;
$target = $direct ?? MEDIAFIRE_FILE_PAGE;

header('Cache-Control: no-store, max-age=0');
header('Referrer-Policy: no-referrer');
header('X-Robots-Tag: noindex, nofollow');
header('Location: ' . $target, true, 302);
exit;
