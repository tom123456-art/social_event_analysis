"""Stable file-based entry point for the Java backend."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from hotspot_etl.inference.sentiment_inference import main

if __name__ == "__main__":
    main()
