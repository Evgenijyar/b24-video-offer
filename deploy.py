#!/usr/bin/env python3
from __future__ import annotations
import subprocess, sys, time, urllib.request

def run(*cmd: str) -> None:
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True)

def main() -> int:
    try:
        run("git", "fetch", "origin")
        run("git", "reset", "--hard", "origin/main")
        run("docker", "compose", "build", "--pull")
        run("docker", "compose", "up", "-d", "--remove-orphans")
        for _ in range(60):
            try:
                with urllib.request.urlopen("http://127.0.0.1:8080/api/health", timeout=3) as r:
                    if r.status == 200:
                        run("docker", "compose", "exec", "-T", "app", "sh", "-lc",
                            "test -x /usr/bin/ffmpeg && test -x /usr/bin/ffprobe && "
                            "/usr/bin/ffmpeg -version | head -n 1 && /usr/bin/ffprobe -version | head -n 1")
                        print("Deployment completed successfully.")
                        run("docker", "compose", "ps")
                        return 0
            except Exception:
                time.sleep(2)
        print("Health check failed. Recent logs:", file=sys.stderr)
        subprocess.run(["docker", "compose", "logs", "--tail=150", "app"])
        return 1
    except subprocess.CalledProcessError as e:
        print(f"Deployment command failed: {e}", file=sys.stderr)
        subprocess.run(["docker", "compose", "logs", "--tail=150", "app"])
        return e.returncode or 1

if __name__ == "__main__":
    raise SystemExit(main())
