#!/usr/bin/env python3
"""Render BeatPlay poster HTML to high-resolution PNG.

Requires: Playwright (pip install playwright)
          python -m playwright install chromium
"""
import asyncio
from pathlib import Path

async def render_poster():
    """Render poster.html to 2160x2700 PNG (2x the 1080x1350 viewport)."""
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        print("Error: Playwright not found. Install with: pip install playwright")
        print("Then run: python -m playwright install chromium")
        return False
    
    root = Path(__file__).resolve().parent
    html_file = root / "poster.html"
    output_file = root / "poster-2160x2700.png"
    
    if not html_file.exists():
        print(f"Error: {html_file} not found")
        return False
    
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(viewport={"width": 1080, "height": 1350}, device_scale_factor=2)
        
        try:
            await page.goto(f"file://{html_file}", wait_until="networkidle")
            await page.screenshot(path=output_file, full_page=False)
            print(f"✓ Poster rendered: {output_file}")
            print(f"  Size: {output_file.stat().st_size / 1024 / 1024:.2f} MB")
            return True
        finally:
            await browser.close()

if __name__ == "__main__":
    result = asyncio.run(render_poster())
    exit(0 if result else 1)
