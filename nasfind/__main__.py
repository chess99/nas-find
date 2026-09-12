import argparse
import logging
import os
import signal
import threading

from .config import load
from .engine import Engine
from .web import Server
from . import __version__


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", action="version", version=f"NAS Find {__version__}")
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    config = load(args.config)
    engine = Engine(config)
    server = Server((config["bind"], config["port"]), engine)

    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    engine.start()
    logging.info("NAS Find listening on %s:%d", config["bind"], config["port"])
    try:
        server.serve_forever()
    finally:
        server.server_close()
        engine.close()


if __name__ == "__main__":
    main()
