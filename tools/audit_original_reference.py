"""Verify a publisher-linked original S6 Edge video; never fit RAW noise from scene texture."""
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import urllib.request

URL = 'https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v002.mp4'
SOURCE = 'https://www.gsmarena.com/samsung_galaxy_s6_edge-review-1236p9.php'
report = {
    'device': 'Samsung Galaxy S6 Edge',
    'publisher': 'GSMArena', 'publisher_page': SOURCE, 'original_video_url': URL,
    'publisher_claim': 'Unmodified video sample straight from the device',
    'calibration_status': 'Reference only: no known ISO, no flat-field/dark sequence, no RAW ISP inversion.',
    'sensor_parameters_changed_from_this_video': False,
}
with tempfile.TemporaryDirectory() as temp:
    video = pathlib.Path(temp) / 'original.mp4'
    try:
        request = urllib.request.Request(URL, headers={'User-Agent': 'GalaxySensorLab-reference-audit/0.5', 'Referer': SOURCE})
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(request, timeout=90) as response, video.open('wb') as output:
            report['resolved_url'] = response.url
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > 250 * 1024 * 1024:
                    raise ValueError('Sample exceeds the 250 MiB audit limit')
                digest.update(chunk)
                output.write(chunk)
        report.update(downloaded=True, bytes=size, sha256=digest.hexdigest())
        probe = subprocess.run(['ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(video)],
                               check=True, capture_output=True, text=True, timeout=60)
        report['container_and_tracks'] = json.loads(probe.stdout)
        # Decode native-size frames. YDIF includes motion and is NOT labelled sensor noise.
        decoded = subprocess.run(['ffmpeg', '-hide_banner', '-i', str(video), '-an', '-frames:v', '15',
                                  '-vf', 'signalstats,metadata=mode=print', '-f', 'null', '-'],
                                 check=True, capture_output=True, text=True, timeout=90)
        values = {}
        for name, value in re.findall(r'lavfi\.signalstats\.(\w+)=([\d.]+)', decoded.stderr):
            values.setdefault(name, []).append(float(value))
        report['decoded_native_frame_statistics'] = values
        report['statistics_note'] = 'Encoded YUV levels. Frame differences contain scene motion, compression and sensor/ISP effects; not a measured noise profile.'
        report['decode_verified'] = True
    except Exception as error:
        report['downloaded'] = report.get('downloaded', False)
        report['audit_error'] = str(error)
        report['decode_verified'] = False
pathlib.Path('reference-audit.json').write_text(json.dumps(report, indent=2))
print(json.dumps({key: report.get(key) for key in ['downloaded', 'bytes', 'sha256', 'decode_verified', 'audit_error']}))
