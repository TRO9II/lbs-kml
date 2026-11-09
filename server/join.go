// Страница-приглашение: ссылку вида http://сервер:8080/join#r=…&c=…&n=… можно отправить
// в любой мессенджер. Данные приглашения — после «#», поэтому на сервер они не уходят.
// Страница открывает приложение, а если его нет — предлагает скачать.
package main

import "net/http"

const joinPage = `<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Приглашение в Рубеж</title>
<style>
:root{--bg:#14171c;--card:#1f232a;--line:#2e343d;--tx:#e9edf2;--tx2:#9aa3ae;--acc:#f0584a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--tx);font:16px/1.45 system-ui,-apple-system,Roboto,sans-serif;padding:24px 16px}
.w{max-width:440px;margin:0 auto}h1{font-size:22px;margin:8px 0 4px}p{color:var(--tx2);margin:6px 0 18px;font-size:14.5px}
.g{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin:16px 0}
.g b{font-size:18px}.b{display:block;text-align:center;text-decoration:none;font-weight:600;border-radius:14px;padding:14px;margin:10px 0;font-size:16px;border:1px solid var(--line);color:var(--tx);background:var(--card)}
.b.a{background:var(--acc);border-color:var(--acc);color:#fff}button.b{width:100%;font-family:inherit;cursor:pointer}
ol{color:var(--tx2);font-size:14px;padding-left:20px}li{margin:4px 0}.m{color:var(--tx2);font-size:12.5px}
</style></head><body><div class="w">
<h1>Приглашение в группу</h1>
<div class="g"><div class="m">Группа</div><b id="n">…</b></div>
<a id="open" class="b a" href="#">Открыть в Рубеже</a>
<a id="apk" class="b" href="https://github.com/TRO9II/lbs-kml/releases/download/latest/Rubezh.apk">Скачать Рубеж (Android)</a>
<button id="cp" class="b" type="button">Скопировать приглашение</button>
<ol>
<li>Нет приложения — нажмите «Скачать Рубеж» и установите (разрешите установку из этого источника).</li>
<li>Вернитесь сюда и нажмите «Открыть в Рубеже».</li>
<li>Не открывается — «Скопировать приглашение», затем в Рубеже: Чат → «Вставить приглашение текстом».</li>
<li>Дождитесь, пока администратор группы примет заявку.</li>
</ol>
<p class="m">Переписка в группе шифруется на телефонах участников.</p>
<a class="b" href="https://t.me/+m4r3-5RMTBA0ZmE6">Канал Рубежа в Telegram</a>
</div><script>
var h=location.hash.replace(/^#/,''),q='s='+encodeURIComponent(location.origin)+(h?'&'+h:''),inv='rubezh2://join?'+q;
var n=(h.match(/(?:^|&)n=([^&]*)/)||[])[1];try{n=decodeURIComponent(n||'')}catch(e){}
document.getElementById('n').textContent=n||'Группа';
var apk=document.getElementById('apk').href,o=document.getElementById('open');
o.href=/Android/i.test(navigator.userAgent)?'intent://join?'+q+'#Intent;scheme=rubezh2;package=ru.lbs.kml;S.browser_fallback_url='+encodeURIComponent(apk)+';end':inv;
document.getElementById('cp').onclick=function(){var b=this;function ok(){b.textContent='Скопировано ✓'}
if(navigator.clipboard)navigator.clipboard.writeText(inv).then(ok,function(){prompt('Скопируйте:',inv)});else prompt('Скопируйте:',inv)};
if(!h)document.querySelector('h1').textContent='Ссылка неполная — попросите новую';
</script></body></html>`

func init() {
	http.HandleFunc("/join", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Write([]byte(joinPage))
	})
}
