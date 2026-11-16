"""Optional renderer for environments where Poppler is already installed."""

import subprocess

from .source import DocumentError


def render_with_poppler(content: bytes, page: int, dpi: int) -> bytes:
    """Render a single PDF page through stdin/stdout without creating files."""
    try:
        process = subprocess.run(
            ["pdftoppm", "-f", str(page), "-l", str(page), "-r", str(dpi),
             "-singlefile", "-png", "-"],
            input=content,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=30,
            check=True,
        )
    except FileNotFoundError:
        raise DocumentError("RENDER_UNAVAILABLE") from None
    except (OSError, subprocess.SubprocessError):
        raise DocumentError("RENDER_FAILED") from None
    return process.stdout
