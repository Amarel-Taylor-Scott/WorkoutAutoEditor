"""Gradio browser UI on top of the same pipeline the CLI uses."""
from __future__ import annotations

import tempfile
from pathlib import Path

from vidcut import editor as editor_mod
from vidcut import llm, pipeline
from vidcut.models import CutPlan


def _format_plan(plan: CutPlan) -> str:
    lines = [f"**{plan.summary}**\n", f"Total: {plan.total_ms / 1000:.1f}s, {len(plan.spans)} spans\n"]
    for i, s in enumerate(plan.spans):
        lines.append(
            f"{i:>2}. {s.start_ms / 1000:6.2f}s - {s.end_ms / 1000:6.2f}s  "
            f"({s.duration_ms / 1000:.2f}s)  {s.rationale or ''}"
        )
    return "\n".join(lines)


def _plan_only(video_path: str, prompt: str, model: str, threshold: float):
    if not video_path:
        return "Upload a video first.", None
    if not prompt.strip():
        return "Enter an editing instruction.", None
    if not editor_mod.have_ffmpeg():
        return "ffmpeg not found on PATH.", None
    try:
        result = pipeline.plan_only(Path(video_path), prompt, model=model, scene_threshold=threshold)
    except RuntimeError as e:
        return f"Error: {e}", None
    return _format_plan(result.plan), result.plan.model_dump()


def _edit(video_path: str, prompt: str, model: str, threshold: float, fast: bool):
    if not video_path or not prompt.strip():
        return None, "Upload a video and enter a prompt."
    out = Path(tempfile.gettempdir()) / f"vidcut-{Path(video_path).stem}-edited.mp4"
    try:
        result = pipeline.edit(
            Path(video_path), prompt, out,
            model=model, scene_threshold=threshold, reencode=not fast,
        )
    except RuntimeError as e:
        return None, f"Error: {e}"
    return str(result.output), _format_plan(result.plan)


def launch(host: str = "127.0.0.1", port: int = 7860, share: bool = False) -> None:
    import gradio as gr

    with gr.Blocks(title="vidcut", theme=gr.themes.Soft()) as demo:
        gr.Markdown("# vidcut\nLocal Gemma-powered video editor. Ollama + ffmpeg.")
        with gr.Row():
            with gr.Column(scale=1):
                video_in = gr.Video(label="Source video", sources=["upload"])
                prompt = gr.Textbox(
                    label="Editing instruction",
                    placeholder='e.g. "Keep only the heaviest sets and skip warmups, max 60 seconds."',
                    lines=3,
                )
                with gr.Row():
                    model = gr.Textbox(label="Ollama model", value=llm.DEFAULT_MODEL)
                    threshold = gr.Slider(0.10, 0.50, value=0.30, step=0.05, label="Scene threshold")
                fast = gr.Checkbox(label="Fast mode (stream-copy, only on keyframes)", value=False)
                with gr.Row():
                    plan_btn = gr.Button("Plan", variant="secondary")
                    edit_btn = gr.Button("Plan + render", variant="primary")
            with gr.Column(scale=1):
                plan_text = gr.Markdown(label="Plan")
                video_out = gr.Video(label="Rendered output", interactive=False)
                json_out = gr.JSON(label="Plan JSON")

        plan_btn.click(_plan_only, [video_in, prompt, model, threshold], [plan_text, json_out])
        edit_btn.click(_edit, [video_in, prompt, model, threshold, fast], [video_out, plan_text])

    demo.launch(server_name=host, server_port=port, share=share, inbrowser=True)
