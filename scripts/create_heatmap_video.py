#!/usr/bin/env python3
"""
ヒートマップPNGファイルから動画を作成するスクリプト

Usage:
    python create_heatmap_video.py <heatmap_dir> <output_file> [fps]

Example:
    python create_heatmap_video.py output/heatmap/Bisectional_1 output/videos/heatmap_Bisectional_1.mp4 2
"""

import os
import sys
import subprocess
import re
from pathlib import Path

def get_timestamp(filename):
    """ファイル名からタイムスタンプを抽出"""
    match = re.search(r'_(\d+)\.png$', filename)
    if match:
        return int(match.group(1))
    return 0

def main():
    if len(sys.argv) < 3:
        print("Usage: python create_heatmap_video.py <heatmap_dir> <output_file> [fps]")
        print("Example: python create_heatmap_video.py output/heatmap/Bisectional_1 output/videos/heatmap.mp4 2")
        sys.exit(1)

    heatmap_dir = sys.argv[1]
    output_file = sys.argv[2]
    fps = int(sys.argv[3]) if len(sys.argv) > 3 else 2  # デフォルト2fps

    # PNGファイルを取得してソート
    png_files = sorted(
        [f for f in os.listdir(heatmap_dir) if f.endswith('.png')],
        key=get_timestamp
    )

    if not png_files:
        print(f"Error: No PNG files found in {heatmap_dir}")
        sys.exit(1)

    print(f"Found {len(png_files)} PNG files")
    print(f"First: {png_files[0]}")
    print(f"Last: {png_files[-1]}")

    # 出力ディレクトリを作成
    os.makedirs(os.path.dirname(output_file), exist_ok=True)

    # 一時的な連番ファイルリストを作成
    list_file = os.path.join(heatmap_dir, "filelist.txt")
    with open(list_file, 'w') as f:
        for png in png_files:
            f.write(f"file '{png}'\n")
            f.write(f"duration {1/fps}\n")
        # 最後のフレームを追加（ffmpegの仕様対策）
        f.write(f"file '{png_files[-1]}'\n")

    # ffmpegで動画作成
    # libx264は幅・高さが偶数である必要があるため、padフィルターで調整
    cmd = [
        'ffmpeg', '-y',
        '-f', 'concat',
        '-safe', '0',
        '-i', list_file,
        '-vf', f'fps={fps},pad=ceil(iw/2)*2:ceil(ih/2)*2',
        '-c:v', 'libx264',
        '-pix_fmt', 'yuv420p',
        output_file
    ]

    print(f"\nCreating video: {output_file}")
    print(f"FPS: {fps}")

    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"ffmpeg error: {result.stderr}")
            sys.exit(1)
        print(f"✓ Video saved to: {output_file}")
    except FileNotFoundError:
        print("Error: ffmpeg not found. Please install ffmpeg.")
        print("  brew install ffmpeg")
        sys.exit(1)
    finally:
        # 一時ファイルを削除
        if os.path.exists(list_file):
            os.remove(list_file)

if __name__ == "__main__":
    main()
