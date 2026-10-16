"""Stable file-based review preparation entry point."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from hotspot_etl.review.review_records import main

if __name__ == "__main__":
    main()
