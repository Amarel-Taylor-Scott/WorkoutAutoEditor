"""vidcut command-line interface."""
from __future__ import annotations

import json
import re
import shlex
from pathlib import Path

import typer
from rich.console import Console
from rich.table import Table

from vidcut import __version__, editor, llm, pipeline
from vidcut.models import (
    AspectPreset,
    EditPolicy,
    OverlayPosition,
    TextOverlay,
    TitleEvery,
    TrimSilence,
)
from vidcut.pipeline import PipelineResult


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(f"vidcut {__version__}")
        raise typer.Exit()


app = typer.Typer(
    add_completion=False,
    help="Local Gemma-powered video editor (Ollama + ffmpeg).",
)
console = Console()


@app.callback()
def _main(
    version: bool = typer.Option(
        None, "--version", "-V",
        callback=_version_callback, is_eager=True,
        help="Show version and exit.",
    ),
) -> None:
    """Root callback - the --version flag fires from here."""


def _check_ffmpeg() -> None:
    if not editor.have_ffmpeg():
        console.print("[red]ffmpeg / ffprobe not found on PATH.[/red]")
        console.print("Install: https://ffmpeg.org/download.html")
        raise typer.Exit(code=2)


_OVERLAY_PATTERN = re.compile(
    r"^(?P<text>[^@]+)@(?P<start>[0-9:.]+)(?:-(?P<end>[0-9:.]+))?(?:#(?P<pos>[a-z_]+))?$"
)


def _parse_overlay(spec: str) -> TextOverlay:
    """Parse `TEXT@MM:SS[-MM:SS][#POSITION]` into a TextOverlay.

    Examples:
      "Set 1@0:05"                          -> at 5s for 3s, top-left default
      "Personal Record@1:30-1:35"           -> 1:30 to 1:35
      "Title@0:00-0:08#center"              -> 0-8s, centered
    """
    m = _OVERLAY_PATTERN.match(spec.strip())
    if not m:
        raise typer.BadParameter(
            f"--title must be 'TEXT@MM:SS[-MM:SS][#POSITION]', got {spec!r}"
        )
    start_ms = _ts_to_ms(m.group("start"))
    end_ms = _ts_to_ms(m.group("end")) if m.group("end") else start_ms + 3_000
    pos_str = m.group("pos") or "top_left"
    try:
        pos = OverlayPosition(pos_str)
    except ValueError:
        raise typer.BadParameter(
            f"position must be one of {[p.value for p in OverlayPosition]}, got {pos_str!r}"
        )
    return TextOverlay(start_ms=start_ms, end_ms=end_ms, text=m.group("text").strip(), position=pos)


def _ts_to_ms(ts: str) -> int:
    """Accept 'SS' or 'MM:SS' or 'MM:SS.mmm'."""
    if ":" in ts:
        parts = ts.split(":")
        mm, ss = parts[0], parts[1]
        return int(mm) * 60_000 + int(float(ss) * 1000)
    return int(float(ts) * 1000)


def _build_policy(
    target_total_s: int | None,
    aspect: str,
    crossfade_ms: int,
    speed: float,
    trim_silence_s: float | None,
    title_every_s: int | None,
) -> EditPolicy:
    return EditPolicy(
        target_total_s=target_total_s,
        aspect=AspectPreset(aspect),
        crossfade_ms=crossfade_ms,
        speed_default=speed,
        trim_silence=TrimSilence(enabled=trim_silence_s is not None, min_dur_s=trim_silence_s or 1.5),
        title_every=TitleEvery(enabled=title_every_s is not None, interval_s=title_every_s or 30),
    )


@app.command()
def plan(
    source: Path = typer.Argument(..., exists=True, dir_okay=False, readable=True, help="Input video"),
    prompt: str = typer.Option(..., "--prompt", "-p", help="Editing instruction"),
    model: str = typer.Option(llm.DEFAULT_MODEL, "--model", "-m", help="Ollama model"),
    threshold: float = typer.Option(0.30, "--threshold", "-t", help="Scene change threshold (0.10 - 0.50)"),
    target_total_s: int | None = typer.Option(None, "--target", help="Target output duration in seconds"),
    aspect: str = typer.Option("source", "--aspect", help="Output aspect: source, 16:9, 9:16, 1:1"),
    crossfade_ms: int = typer.Option(0, "--crossfade-ms", help="Cross-fade duration between spans"),
    speed: float = typer.Option(1.0, "--speed", help="Default playback speed multiplier"),
    trim_silence: float | None = typer.Option(None, "--trim-silence", help="Drop silences of at least N seconds"),
    title_every: int | None = typer.Option(None, "--title-every", help="Show a timestamp title every N seconds"),
    as_json: bool = typer.Option(False, "--json", help="Print the plan as JSON instead of a table"),
):
    """Compute a cut plan and print it. Does not render."""
    _check_ffmpeg()
    policy = _build_policy(target_total_s, aspect, crossfade_ms, speed, trim_silence, title_every)
    console.print(f"[dim]Probing[/dim] {source.name}")
    try:
        result = pipeline.plan_only(source, prompt, model=model, scene_threshold=threshold, policy=policy)
    except RuntimeError as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(code=2)
    if as_json:
        typer.echo(result.plan.model_dump_json(indent=2))
    else:
        _print_plan(result)


@app.command()
def edit(
    source: Path = typer.Argument(..., exists=True, dir_okay=False, readable=True, help="Input video"),
    output: Path = typer.Option(..., "--output", "-o", help="Output mp4"),
    prompt: str = typer.Option(..., "--prompt", "-p", help="Editing instruction"),
    model: str = typer.Option(llm.DEFAULT_MODEL, "--model", "-m", help="Ollama model"),
    threshold: float = typer.Option(0.30, "--threshold", "-t", help="Scene change threshold"),
    fast: bool = typer.Option(False, "--fast", help="Stream-copy without re-encoding"),
    target_total_s: int | None = typer.Option(None, "--target", help="Target output duration in seconds"),
    aspect: str = typer.Option("source", "--aspect", help="Output aspect: source, 16:9, 9:16, 1:1"),
    crossfade_ms: int = typer.Option(0, "--crossfade-ms", help="Cross-fade duration between spans"),
    speed: float = typer.Option(1.0, "--speed", help="Default playback speed multiplier"),
    trim_silence: float | None = typer.Option(None, "--trim-silence", help="Drop silences of at least N seconds"),
    title_every: int | None = typer.Option(None, "--title-every", help="Show a timestamp title every N seconds"),
    title: list[str] = typer.Option(None, "--title", help="Add a manual title: 'TEXT@MM:SS[-MM:SS][#POSITION]'"),
    dry_run: bool = typer.Option(False, "--dry-run", help="Print ffmpeg command without executing"),
):
    """Plan and render the edit to OUTPUT."""
    _check_ffmpeg()
    policy = _build_policy(target_total_s, aspect, crossfade_ms, speed, trim_silence, title_every)
    overlays = [_parse_overlay(t) for t in (title or [])]
    console.print(f"[dim]Editing[/dim] {source.name} -> {output.name}")
    try:
        result = pipeline.edit(
            source, prompt, output,
            model=model, scene_threshold=threshold, reencode=not fast,
            policy=policy, dry_run=dry_run,
        )
    except RuntimeError as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(code=2)
    # Append any user-supplied --title overlays into the plan after policy.
    if overlays and not dry_run:
        # Re-render with the manual overlays merged in.
        merged = result.plan.model_copy(update={
            "global_overlays": list(result.plan.global_overlays) + overlays,
        })
        rendered = editor.render(source, merged, output, reencode=not fast, dry_run=False)
        assert isinstance(rendered, Path)
    _print_plan(result)
    if dry_run:
        # In dry-run editor returned the command, but we routed through edit()
        # which already discarded it. Rebuild from the plan for the printout.
        cmd = editor.render(source, result.plan, output, reencode=not fast, dry_run=True)
        console.print("[dim]ffmpeg command:[/dim]")
        console.print(" ".join(shlex.quote(str(a)) for a in cmd))
    else:
        console.print(f"[green]Wrote[/green] {output} ({result.plan.total_ms / 1000:.1f}s)")


@app.command()
def serve(
    host: str = typer.Option("127.0.0.1", help="Bind host"),
    port: int = typer.Option(7860, help="Bind port"),
    share: bool = typer.Option(False, "--share", help="Open a public Gradio share link"),
):
    """Launch the Gradio web UI at http://host:port/."""
    try:
        from vidcut.webapp import launch
    except ImportError as e:
        console.print(f"[red]Gradio not installed.[/red] Run: pip install 'vidcut[ui]'")
        raise typer.Exit(code=2) from e
    launch(host=host, port=port, share=share)


def _print_plan(result: PipelineResult) -> None:
    table = Table(
        title=f"Cut plan ({len(result.plan.spans)} spans, {result.plan.total_ms / 1000:.1f}s total, aspect={result.plan.aspect.value})"
    )
    table.add_column("#", justify="right")
    table.add_column("Start")
    table.add_column("End")
    table.add_column("Duration")
    table.add_column("Speed", justify="right")
    table.add_column("Overlays", justify="right")
    table.add_column("Rationale", overflow="fold")
    for i, s in enumerate(result.plan.spans):
        table.add_row(
            str(i),
            f"{s.start_ms / 1000:.2f}s",
            f"{s.end_ms / 1000:.2f}s",
            f"{s.duration_ms / 1000:.2f}s",
            f"{s.speed:.2f}x" if s.speed != 1.0 else "1x",
            str(len(s.overlays)) if s.overlays else "-",
            s.rationale or "-",
        )
    console.print(table)
    if result.plan.global_overlays:
        console.print(f"[dim]+ {len(result.plan.global_overlays)} global overlay(s)[/dim]")
    if result.plan.summary:
        console.print(f"[bold]Summary:[/bold] {result.plan.summary}")


if __name__ == "__main__":
    app()
