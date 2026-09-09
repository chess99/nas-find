"""Check HTTP liveness independently of a potentially long first index build."""
import urllib.request

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open("http://127.0.0.1:8765/", timeout=3) as response:
    if response.status != 200:
        raise SystemExit(1)
