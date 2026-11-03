"""Give changed files back the line endings they have in git HEAD (CRLF or LF).

Some editors and shell tools rewrite CRLF files with LF; this keeps diffs to real changes.
New files take the line ending most files in their folder use.
Usage: python scripts/fix-eol.py
"""
import pathlib
import subprocess


def run(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def to(data: bytes, crlf: bool) -> bytes:
    data = data.replace(b"\r\n", b"\n")
    return data.replace(b"\n", b"\r\n") if crlf else data


def main() -> None:
    changed = [l[3:] for l in run("status", "--porcelain", "-uall").splitlines() if l and l[3:] and not l.startswith("D")]
    for name in changed:
        path = pathlib.Path(name)
        if not path.is_file():
            continue
        data = path.read_bytes()
        if b"\0" in data:
            continue
        try:
            head = subprocess.run(["git", "show", f"HEAD:{name}"], capture_output=True, check=True).stdout
            crlf = b"\r\n" in head
        except subprocess.CalledProcessError:
            siblings = [p for p in path.parent.glob(f"*{path.suffix}") if p != path and p.is_file()]
            crlf = sum(b"\r\n" in p.read_bytes() for p in siblings) * 2 > len(siblings) > 0
        fixed = to(data, crlf)
        if fixed != data:
            path.write_bytes(fixed)
            print(f"{name}: {'CRLF' if crlf else 'LF'}")


if __name__ == "__main__":
    main()
