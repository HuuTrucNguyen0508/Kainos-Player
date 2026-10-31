#!/usr/bin/env python3
"""Inspect retained Perfetto traces without running the app or timing the parser."""
import argparse
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--processor', required=True, help='Perfetto trace_processor_shell binary')
parser.add_argument('trace')
args = parser.parse_args()
query = """
SELECT t.name AS thread, s.name, count(*) AS n,
       round(avg(s.dur)/1e6,3) AS wall_ms
FROM slice s
JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid
WHERE p.name='com.universalmusic.player' AND s.dur>0
  AND (t.name='RenderThread' OR s.name IN
       ('postAndWait','Compose:recompose','AndroidOwner:measureAndLayout'))
GROUP BY t.name,s.name ORDER BY sum(s.dur) DESC LIMIT 15
"""
subprocess.run([args.processor, 'query', args.trace, query], check=True)
