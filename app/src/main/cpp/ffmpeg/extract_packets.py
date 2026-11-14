#!/usr/bin/env python3
"""不依赖 ffprobe：直接解析 m4a 盒结构，抽出 ALAC cookie 与各样本（含分片 MP4）。

容器格式（全大端）：4B cookie 长度 | cookie | 重复 [4B 样本长度 | 样本]
"""
import struct
import sys

STBL = ["moov", "trak", "mdia", "minf", "stbl"]


def boxes(data, start, end):
    at = start
    while at + 8 <= end:
        size, kind = struct.unpack(">I4s", data[at:at + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[at + 8:at + 16])[0]
            header = 16
        elif size == 0:
            size = end - at
        if size < header or at + size > end:
            return
        yield kind.decode("latin-1"), at, at + header, at + size
        at += size


def find(data, start, end, path):
    for kind, box, body, stop in boxes(data, start, end):
        if kind == path[0]:
            if len(path) == 1:
                return body, stop
            found = find(data, body, stop, path[1:])
            if found:
                return found
    return None


def cookie_from_stsd(data):
    """stsd 里第一个样本描述的 alac 原子头（36 字节：size + "alac" + version + 24B 配置）。"""
    body, _ = find(data, 0, len(data), STBL + ["stsd"])
    # 样本描述：8B 盒头 + 28B 音频样本描述字段（含声道、位深、采样率）。
    atom = body + 8 + 36
    atom_size = struct.unpack(">I", data[atom:atom + 4])[0]
    cookie = data[atom:atom + atom_size]
    assert cookie[4:8] == b"alac", "stsd 中未找到 alac 原子头"
    return cookie


def samples_from_stbl(data):
    body, _ = find(data, 0, len(data), STBL + ["stsz"])
    _, uniform, count = struct.unpack(">III", data[body:body + 12])
    if count == 0:
        return []
    sizes = ([uniform] * count) if uniform else list(
        struct.unpack(">%dI" % count, data[body + 12:body + 12 + 4 * count]))

    located = find(data, 0, len(data), STBL + ["co64"])
    if located:
        body, _ = located
        n = struct.unpack(">I", data[body + 4:body + 8])[0]
        offsets = list(struct.unpack(">%dQ" % n, data[body + 8:body + 8 + 8 * n]))
    else:
        body, _ = find(data, 0, len(data), STBL + ["stco"])
        n = struct.unpack(">I", data[body + 4:body + 8])[0]
        offsets = list(struct.unpack(">%dI" % n, data[body + 8:body + 8 + 4 * n]))
    return list(zip(offsets, sizes))[:count]


def samples_from_fragments(data):
    """分片 MP4：解析每个 moof/traf/trun，偏移相对 moof 起始（含 8B 盒头）。"""
    result = []
    for kind, moof, body, stop in boxes(data, 0, len(data)):
        if kind != "moof":
            continue
        for k2, traf, tbody, tstop in boxes(data, body, stop):
            if k2 != "traf":
                continue
            default_size = 0
            base_offset = 0
            runs = []
            for k3, run, rbody, rstop in boxes(data, tbody, tstop):
                if k3 == "tfhd":
                    flags = struct.unpack(">I", data[rbody:rbody + 4])[0] & 0xffffff
                    at = rbody + 8
                    if flags & 0x1: at += 8          # base-data-offset
                    if flags & 0x2: at += 4          # sample-description-index
                    if flags & 0x8: default_size = struct.unpack(">I", data[at:at + 4])[0]
                elif k3 == "trun":
                    runs.append((rbody, rstop))
            if not runs:
                continue
            cursor = None
            for rbody, _ in runs:
                flags = struct.unpack(">I", data[rbody:rbody + 4])[0] & 0xffffff
                count = struct.unpack(">I", data[rbody + 4:rbody + 8])[0]
                at = rbody + 8
                if flags & 0x1:
                    base_offset = moof + struct.unpack(">i", data[at:at + 4])[0]
                    at += 4
                elif cursor is not None:
                    base_offset = cursor
                cursor = base_offset
                for _ in range(count):
                    if flags & 0x100: at += 4    # sample-duration
                    size = default_size
                    if flags & 0x200:
                        size = struct.unpack(">I", data[at:at + 4])[0]
                        at += 4
                    if flags & 0x400: at += 4    # sample-flags
                    if flags & 0x800: at += 4    # composition offset
                    result.append((cursor, size))
                    cursor += size
    return result


def main(src, out):
    data = open(src, "rb").read()
    cookie = cookie_from_stsd(data)
    samples = samples_from_stbl(data) or samples_from_fragments(data)
    total = sum(size for _, size in samples)
    print(f"cookie={len(cookie)}B samples={len(samples)} bytes={total}")

    with open(out, "wb") as fh:
        fh.write(struct.pack(">I", len(cookie)))
        fh.write(cookie)
        for offset, size in samples:
            fh.write(struct.pack(">I", size))
            fh.write(data[offset:offset + size])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
