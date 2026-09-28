from __future__ import annotations

from pathlib import Path


class PathValidationError(Exception):
    def __init__(self, category: str, message: str) -> None:
        super().__init__(message)
        self.category = category
        self.message = message


def resolve_under_root(candidate: Path, root: Path) -> Path:
    """Resolve candidate and require it to be a regular file under root.

    Rejects path traversal, symlinks that escape the root, directories, and
    missing/unreadable files. Public errors must not include absolute host paths.
    """
    root_resolved = root.resolve()
    if not root_resolved.is_dir():
        raise PathValidationError("music_root_invalid", "Music root is not a readable directory")

    try:
        # resolve(strict=True) follows symlinks; we then re-check containment.
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError as exc:
        raise PathValidationError("missing", "Configured test track was not found") from exc
    except OSError as exc:
        raise PathValidationError("unreadable", "Configured test track is not readable") from exc

    try:
        resolved.relative_to(root_resolved)
    except ValueError as exc:
        raise PathValidationError("outside_root", "Configured test track is outside the music root") from exc

    if resolved.is_symlink():
        # resolve() already followed links; still reject if the original path is a symlink
        # that points outside after resolution (already covered) or if we want no symlinks.
        # Policy for the spike: allow only if the final resolved path stays under root.
        pass

    if not resolved.is_file():
        raise PathValidationError("not_a_file", "Configured test track is not a regular file")

    if not os_access_readable(resolved):
        raise PathValidationError("unreadable", "Configured test track is not readable")

    return resolved


def os_access_readable(path: Path) -> bool:
    try:
        with path.open("rb"):
            return True
    except OSError:
        return False
