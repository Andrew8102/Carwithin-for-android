#!/usr/bin/env python3
"""极简 AndroidManifest.xml（二进制 AXML）解析器。

用途：`adb pull` 出目标 App 的 base.apk 后，不依赖 aapt / Android SDK，
直接列出全部 <activity> 及其 android:exported / intent-filter action。

用法：
    python axml.py path/to/base.apk            # 从 APK 里读 AndroidManifest.xml
    python axml.py path/to/AndroidManifest.xml # 直接读 AXML 文件

输出：JSON（activities 数组 + 主入口）
"""
import json
import struct
import sys
import zipfile

# chunk 类型
RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_RESOURCE_MAP = 0x0180

UTF8_FLAG = 0x100

# android 属性资源 id
ATTR_NAME = 0x01010003
ATTR_EXPORTED = 0x01010010
ATTR_TARGET_ACTIVITY = 0x01010202


def _read_string_pool(data, start):
    """解析字符串池，返回 (字符串列表, 池结束偏移)。"""
    header_size = struct.unpack_from("<H", data, start + 2)[0]
    size = struct.unpack_from("<I", data, start + 4)[0]
    string_count = struct.unpack_from("<I", data, start + 8)[0]
    flags = struct.unpack_from("<I", data, start + 16)[0]
    strings_start = struct.unpack_from("<I", data, start + 20)[0]
    utf8 = bool(flags & UTF8_FLAG)

    offsets = []
    base = start + header_size
    for i in range(string_count):
        offsets.append(struct.unpack_from("<I", data, base + i * 4)[0])

    strings = []
    data_base = start + strings_start
    for off in offsets:
        pos = data_base + off
        if utf8:
            # 先 1~2 字节 utf16 长度，再 1~2 字节 utf8 长度
            n = data[pos]
            pos += 2 if (n & 0x80) else 1
            ln = data[pos]
            if ln & 0x80:
                ln = ((ln & 0x7F) << 8) | data[pos + 1]
                pos += 2
            else:
                pos += 1
            strings.append(data[pos:pos + ln].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                pos += 2
            strings.append(data[pos:pos + n * 2].decode("utf-16-le", "replace"))
    return strings, start + size


def parse(data):
    """解析 AXML，返回 [{'tag','name','exported','actions'}] 事件列表。"""
    if len(data) < 8:
        raise ValueError("文件太短，不是 AXML")
    file_type, header_size, total = struct.unpack_from("<HHI", data, 0)
    if file_type != RES_XML:
        raise ValueError("不是二进制 AXML（type=0x%04x）" % file_type)

    strings = []
    res_map = []
    # 元素栈：每项 {'tag','attrs','actions'}
    stack = []
    result = []

    pos = header_size
    while pos + 8 <= len(data):
        ctype, chdr, csize = struct.unpack_from("<HHI", data, pos)
        if csize <= 0 or pos + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            strings, _ = _read_string_pool(data, pos)
        elif ctype == RES_XML_RESOURCE_MAP:
            n = (csize - chdr) // 4
            res_map = list(struct.unpack_from("<%dI" % n, data, pos + chdr))
        elif ctype == RES_XML_START_ELEMENT:
            ns_idx, name_idx = struct.unpack_from("<II", data, pos + 16)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + 24)
            tag = strings[name_idx] if name_idx < len(strings) else "?"
            attrs = {}
            ap = pos + 16 + attr_start
            for i in range(attr_count):
                a_ns, a_name, a_raw = struct.unpack_from("<III", data, ap + i * attr_size)
                a_type = data[ap + i * attr_size + 15]
                a_data = struct.unpack_from("<I", data, ap + i * attr_size + 16)[0]
                key = None
                # 优先用资源 id（最可靠），退回属性名字符串
                if a_name < len(res_map):
                    key = "res:0x%08x" % res_map[a_name]
                if a_name < len(strings):
                    plain = strings[a_name]
                    key = key or plain
                if a_type == 0x03 and a_data < len(strings):
                    value = strings[a_data]
                elif a_type == 0x12:
                    value = bool(a_data)
                elif a_type == 0x01:
                    value = "0x%08x" % a_data
                else:
                    value = a_data
                if key:
                    attrs[key] = value
            stack.append({"tag": tag, "attrs": attrs, "actions": []})
        elif ctype == RES_XML_END_ELEMENT:
            if stack:
                node = stack.pop()
                result.append(node)
                # action / category 归属最近的 activity（跳过 intent-filter 这一层）
                if node["tag"] in ("action", "category"):
                    value = _attr(node["attrs"], ATTR_NAME, "name")
                    if isinstance(value, str):
                        bucket = "_actions" if node["tag"] == "action" else "_categories"
                        for parent in reversed(stack):
                            if parent["tag"] in ("activity", "activity-alias"):
                                parent.setdefault(bucket, []).append(value)
                                break
        pos += csize
    return result


def _attr(attrs, rid, plain=None):
    v = attrs.get("res:0x%08x" % rid)
    if v is None and plain:
        v = attrs.get(plain)
    return v


def extract_activities(events):
    """从解析结果里提取 activity 列表。"""
    acts = []
    for node in events:
        if node["tag"] not in ("activity", "activity-alias"):
            continue
        attrs = node["attrs"]
        name = _attr(attrs, ATTR_NAME, "name")
        if not isinstance(name, str):
            continue
        exported = _attr(attrs, ATTR_EXPORTED, "exported")
        target = _attr(attrs, ATTR_TARGET_ACTIVITY, "targetActivity")
        actions = node.get("_actions", [])
        categories = node.get("_categories", [])
        acts.append({
            "name": name,
            "exported": exported if isinstance(exported, bool) else None,
            "target": target if isinstance(target, str) else None,
            "actions": sorted(set(actions)),
            "categories": sorted(set(categories)),
            "has_launcher": any("LAUNCHER" in c for c in categories),
        })
    # 疑似 CarLife 入口排在前面
    kw = ("carlife", "cast", "mirror", "link", "projection", "screen", "connect", "carplay")

    def score(a):
        n = (a["name"] or "").lower()
        return (0 if any(k in n for k in kw) else 1,
                0 if a["exported"] else 1,
                a["name"])

    acts.sort(key=score)
    return acts


def load_manifest(path):
    if path.lower().endswith(".apk"):
        with zipfile.ZipFile(path) as z:
            return z.read("AndroidManifest.xml")
    with open(path, "rb") as f:
        return f.read()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    data = load_manifest(sys.argv[1])
    events = parse(data)
    acts = extract_activities(events)
    pkg = None
    for node in events:
        if node["tag"] == "manifest":
            pkg = node["attrs"].get("package")
            break
    out = {
        "package": pkg,
        "total": len(acts),
        "exported": sum(1 for a in acts if a["exported"]),
        "launcher": [a["name"] for a in acts if a["has_launcher"]],
        "activities": acts,
    }
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
