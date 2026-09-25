#!/usr/bin/env python3
"""Generates guide/index.html (en) and guide/<lang>/index.html from TRANSLATIONS.
Run from repo root:  python3 guide/_build/generate.py
The _build folder is ignored by GitHub Pages (Jekyll skips underscore dirs).
"""
import os, html

LANG_NAMES = {
    "en": "English", "ko": "한국어", "ja": "日本語", "zh-CN": "简体中文", "zh-TW": "繁體中文",
    "es": "Español", "pt-BR": "Português (Brasil)", "fr": "Français", "de": "Deutsch",
    "ru": "Русский", "id": "Bahasa Indonesia", "vi": "Tiếng Việt", "th": "ไทย",
    "tr": "Türkçe", "ar": "العربية", "hi": "हिन्दी",
}
ORDER = ["en", "ko", "ja", "zh-CN", "zh-TW", "es", "pt-BR", "fr", "de", "ru", "id", "vi", "th", "tr", "ar", "hi"]
RTL = {"ar"}
# 가이드는 프로젝트 페이지(하위 경로)로 서빙된다. 루트 절대경로를 직접 쓰면
# 옛 주소(https://aidanpark.github.io/guide/)로 새므로 아래 값에서만 파생시킨다.
SITE = "https://aidanpark.github.io"        # 개발자 사이트(루트) — app-ads.txt 가 있는 곳
GUIDE_PATH = "/screen-translator/guide/"    # 이 저장소의 Pages 경로
BASE = SITE + GUIDE_PATH

TEMPLATE = """<!DOCTYPE html>
<html lang="{lang}"{dir_attr}>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="description" content="{meta}">
<title>{title}</title>
{hreflangs}
<style>
  :root {{ color-scheme: light dark; }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
    line-height: 1.65; background: #fafafa; color: #1a1a1a;
  }}
  @media (prefers-color-scheme: dark) {{
    body {{ background: #111; color: #eee; }}
    .card, .step, .faq details {{ background: #1c1c1e !important; }}
    .badge {{ background: #333 !important; }}
    .langsel {{ background: #1c1c1e; color: #eee; border-color: #444; }}
  }}
  main {{ max-width: 760px; margin: 0 auto; padding: 2rem 1.2rem 4rem; }}
  a {{ color: #01875f; }}
  h1 {{ font-size: 1.7rem; margin: .8rem 0 .3rem; }}
  .sub {{ opacity: .7; margin-bottom: 2rem; }}
  h2 {{ font-size: 1.25rem; margin: 2.4rem 0 .8rem; }}
  p {{ margin-bottom: .7rem; }}
  .topbar {{ display: flex; justify-content: space-between; align-items: center; gap: 1rem; }}
  .backlink {{ font-size: .9rem; }}
  .langsel {{
    font-size: .85rem; padding: .25rem .5rem; border-radius: 8px;
    border: 1px solid #ccc; background: #fff; color: inherit;
  }}
  .store {{
    display: inline-block; background: #01875f; color: #fff; text-decoration: none;
    padding: .55rem 1.2rem; border-radius: 24px; font-weight: 600; font-size: .95rem; margin-top: .6rem;
  }}
  .step {{
    background: #fff; border-radius: 14px; padding: 1rem 1.2rem; margin-bottom: .8rem;
    box-shadow: 0 1px 8px rgba(0,0,0,.07); display: flex; gap: .9rem; align-items: flex-start;
  }}
  .num {{
    flex: 0 0 auto; width: 1.8rem; height: 1.8rem; border-radius: 50%;
    background: #01875f; color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center;
  }}
  .card {{
    background: #fff; border-radius: 14px; padding: 1.1rem 1.2rem; margin-bottom: 1rem;
    box-shadow: 0 1px 8px rgba(0,0,0,.07);
  }}
  .mode {{ display: flex; gap: 1rem; align-items: flex-start; flex-wrap: wrap; }}
  .mode video {{
    flex: 0 0 200px; width: 200px; max-width: 100%; border-radius: 10px; background: #000;
  }}
  .mode .txt {{ flex: 1 1 260px; }}
  .mode h3 {{ font-size: 1.05rem; margin-bottom: .3rem; }}
  .badge {{
    display: inline-block; font-size: .75rem; background: #e8f3ef; border-radius: 8px;
    padding: .1rem .5rem; margin-left: .4rem; vertical-align: middle; opacity: .9;
  }}
  ul, ol {{ padding-left: 1.3rem; margin-bottom: .7rem; }}
  li {{ margin-bottom: .3rem; }}
  .faq details {{
    background: #fff; border-radius: 12px; padding: .8rem 1.1rem; margin-bottom: .6rem;
    box-shadow: 0 1px 8px rgba(0,0,0,.07);
  }}
  .faq summary {{ cursor: pointer; font-weight: 600; }}
  .faq details p, .faq details ul {{ margin-top: .5rem; }}
  footer {{ margin-top: 3rem; font-size: .85rem; opacity: .6; }}
  footer a {{ color: inherit; }}
</style>
</head>
<body>
<main>
  <div class="topbar">
    <p class="backlink"><a href="{site}/">&larr; BoxBee</a></p>
    <select class="langsel" id="langSel" aria-label="Language">{lang_options}</select>
  </div>
  <h1>{h1}</h1>
  <p class="sub">{sub}</p>

  <h2>{qs_h}</h2>
  <div class="step"><div class="num">1</div><div>{s1}</div></div>
  <div class="step"><div class="num">2</div><div>{s2}</div></div>
  <div class="step"><div class="num">3</div><div>{s3}</div></div>

  <h2>{mb_h}</h2>
  <div class="card">
    <p>{mb_p1}</p>
    <ul>
      <li>{mb_li1}</li>
      <li>{mb_li2}</li>
      <li>{mb_li3}</li>
      <li>{mb_li4}</li>
      <li>{mb_li5}</li>
    </ul>
    <p>{mb_p2}</p>
  </div>

  <h2>{modes_h}</h2>
  <p>{modes_p}</p>

  <div class="card mode">
    <video muted autoplay loop playsinline preload="metadata" poster="{media}word.jpg" src="{media}word.mp4"></video>
    <div class="txt"><h3>{word_h}</h3>
    <p>{word_p}</p></div>
  </div>
  <div class="card mode">
    <video muted autoplay loop playsinline preload="metadata" poster="{media}sentence.jpg" src="{media}sentence.mp4"></video>
    <div class="txt"><h3>{sen_h}</h3>
    <p>{sen_p}</p></div>
  </div>
  <div class="card mode">
    <video muted autoplay loop playsinline preload="metadata" poster="{media}paragraph.jpg" src="{media}paragraph.mp4"></video>
    <div class="txt"><h3>{par_h}</h3>
    <p>{par_p}</p></div>
  </div>
  <div class="card mode">
    <video muted autoplay loop playsinline preload="metadata" poster="{media}select.jpg" src="{media}select.mp4"></video>
    <div class="txt"><h3>{sel_h}</h3>
    <p>{sel_p}</p></div>
  </div>
  <div class="card mode">
    <video muted autoplay loop playsinline preload="metadata" poster="{media}fixed_area.jpg" src="{media}fixed_area.mp4"></video>
    <div class="txt"><h3>{fix_h} <span class="badge">{fix_badge}</span></h3>
    <p>{fix_p}</p></div>
  </div>

  <h2>{lang_h}</h2>
  <p>{lang_p}</p>
  <p>{lang_p2}</p>

  <h2>{ai_h}</h2>
  <div class="card">
    <p>{ai_p1}</p>
    <ul>
      <li>{ai_li1}</li>
      <li>{ai_li2}</li>
      <li>{ai_li3}</li>
      <li>{ai_li4}</li>
    </ul>
    <p>{ai_p2}</p>
    <p>{ai_p3}</p>
    <p>{ai_fail}</p>
  </div>

  <h2>{tts_h}</h2>
  <div class="card">
    <p>{tts_p1}</p>
    <p>{tts_p2}</p>
  </div>

  <h2>{rp_h}</h2>
  <div class="card">
    <p>{rp_p1}</p>
    <p>{rp_p2}</p>
  </div>

  <h2>{faq_h}</h2>
  <div class="faq">
    <details><summary>{f1_q}</summary>
      <p>{f1_a}</p></details>
    <details><summary>{f2_q}</summary>
      <p>{f2_a}</p></details>
    <details><summary>{fauto_q}</summary>
      <p>{fauto_a}</p></details>
    <details><summary>{f3_q}</summary>
      <p>{f3_a}</p></details>
    <details><summary>{f4_q}</summary>
      <p>{f4_a}</p></details>
    <details><summary>{ffail_q}</summary>
      <p>{ffail_a}</p></details>
    <details><summary>{f5_q}</summary>
      <p>{f5_a}</p></details>
    <details><summary>{ftips_q}</summary>
      <p>{ftips_a}</p></details>
  </div>

  <h2>{os_h}</h2>
  <div class="card">
    <p>{os_p}</p>
  </div>

  <h2>{stuck_h}</h2>
  <p>{stuck_p}</p>
  <a class="store" href="https://play.google.com/store/apps/details?id=com.galaxy.airviewdictionary">{store_btn}</a>

  <footer>© 2026 BoxBee Corp. · <a href="{site}/privacy.html">{privacy}</a></footer>
</main>
<script>
(function () {{
  var sel = document.getElementById('langSel');
  sel.addEventListener('change', function () {{
    var v = sel.value;
    try {{ localStorage.setItem('guideLang', v); }} catch (e) {{}}
    location.href = v === 'en' ? '{guide_path}' : '{guide_path}' + v + '/';
  }});
{redirect}}})();
</script>
</body>
</html>
"""

REDIRECT_JS = """  var GUIDE_PATH_JS = '{guide_path}';
  // 첫 방문 시 브라우저 언어에 맞는 번역 페이지로 이동 (언어를 직접 고르면 그 선택을 기억)
  var LANGS = {"ko":1,"ja":1,"zh-CN":1,"zh-TW":1,"es":1,"pt-BR":1,"fr":1,"de":1,"ru":1,"id":1,"vi":1,"th":1,"tr":1,"ar":1,"hi":1};
  var saved = null;
  try { saved = localStorage.getItem('guideLang'); } catch (e) {}
  if (saved === 'en') return;
  if (saved && LANGS[saved]) { location.replace(GUIDE_PATH_JS + saved + '/'); return; }
  if (saved) return;
  var nav = (navigator.languages && navigator.languages[0]) || navigator.language || '';
  var t = null;
  if (LANGS[nav]) t = nav;
  else {
    var b = nav.split('-')[0];
    if (b === 'zh') t = (/tw|hk|mo|hant/i.test(nav)) ? 'zh-TW' : 'zh-CN';
    else if (b === 'pt') t = 'pt-BR';
    else if (LANGS[b]) t = b;
  }
  if (t) location.replace(GUIDE_PATH_JS + t + '/');
"""

from translations import TRANSLATIONS  # noqa: E402

def build():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # guide/
    hreflangs = ["<link rel=\"alternate\" hreflang=\"x-default\" href=\"%s\">" % BASE]
    for code in ORDER:
        href = BASE if code == "en" else BASE + code + "/"
        hreflangs.append('<link rel="alternate" hreflang="%s" href="%s">' % (code, href))
    hreflangs_html = "\n".join(hreflangs)

    for code in ORDER:
        t = TRANSLATIONS[code]
        options = []
        for c in ORDER:
            selected = " selected" if c == code else ""
            options.append('<option value="%s"%s>%s</option>' % (c, selected, html.escape(LANG_NAMES[c])))
        page = TEMPLATE.format(
            lang=code,
            dir_attr=' dir="rtl"' if code in RTL else "",
            hreflangs=hreflangs_html,
            lang_options="".join(options),
            media="media/" if code == "en" else "../media/",
            site=SITE,
            guide_path=GUIDE_PATH,
            # REDIRECT_JS 에는 JS 객체 리터럴 중괄호가 있어 .format() 을 쓸 수 없다.
            redirect=REDIRECT_JS.replace("{guide_path}", GUIDE_PATH) if code == "en" else "",
            **t,
        )
        out = os.path.join(root, "index.html") if code == "en" else os.path.join(root, code, "index.html")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            f.write(page)
        print("wrote", os.path.relpath(out, root))

if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    build()
