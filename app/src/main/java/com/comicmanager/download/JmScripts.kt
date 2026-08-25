package com.comicmanager.download

const val JS_FIND_BUTTON = """
(function() {
    function getRect(el) {
        var r = el.getBoundingClientRect();
        return { tag: el.tagName.toLowerCase(), text: (el.textContent||'').trim().substring(0,30),
                 x: r.left, y: r.top, w: r.width, h: r.height,
                 cx: r.left + r.width/2, cy: r.top + r.height/2 };
    }
    
    function isVisible(el) {
        if (!el || el.offsetWidth < 5 || el.offsetHeight < 5) return false;
        var r = el.getBoundingClientRect();
        return r.bottom > 0 && r.top < window.innerHeight;
    }
    
    // Strategy 1: find toolbar container by known text ("喜欢" or "收藏")
    var toolbar = null;
    var labels = document.querySelectorAll('*');
    for (var i = 0; i < labels.length; i++) {
        var t = (labels[i].textContent || '');
        if ((t.indexOf('喜欢') >= 0 || t.indexOf('喜歡') >= 0) && t.indexOf('收藏') >= 0) {
            toolbar = labels[i];
            break;
        }
    }
    
    if (toolbar) {
        // Find all buttons/clickable items inside toolbar
        var btns = toolbar.querySelectorAll('button, a, [onclick], [role="button"], li, .item, .tab, > *');
        var visibleBtns = [];
        for (var b = 0; b < btns.length; b++) {
            if (isVisible(btns[b])) {
                var info = getRect(btns[b]);
                info.index = b;
                info.text = (btns[b].textContent || '').trim().substring(0, 20);
                visibleBtns.push(info);
            }
        }
        
        if (visibleBtns.length >= 5) {
            // 喜欢=0, 评论=1, 观看=2, 收藏=3, 下载=4 (5 items total)
            var dlBtn = visibleBtns[4]; // 5th item (0-indexed: 4)
            dlBtn.matchedBy = 'toolbar-index-5';
            return JSON.stringify(dlBtn);
        }
        
        if (visibleBtns.length > 0) {
            // If less than 5 items visible, try the last one
            var lastBtn = visibleBtns[visibleBtns.length - 1];
            lastBtn.matchedBy = 'toolbar-last';
            return JSON.stringify(lastBtn);
        }
    }
    
    // Strategy 2: find toolbar by class names
    var toolClasses = ['.toolbar', '.tool-bar', '.action-bar', '.bottom-bar', '.footer', '[class*="tool"]', '[class*="Tab"]', '[class*="bar"]'];
    for (var ci = 0; ci < toolClasses.length; ci++) {
        try {
            var containers = document.querySelectorAll(toolClasses[ci]);
            for (var cj = 0; cj < containers.length; cj++) {
                var c = containers[cj];
                if (!isVisible(c)) continue;
                var cBtns = c.querySelectorAll('button, a, [onclick], li, .item, > *');
                if (cBtns.length >= 4) {
                    var last = cBtns[cBtns.length - 1];
                    if (isVisible(last)) {
                        var linfo = getRect(last);
                        linfo.matchedBy = 'class:' + toolClasses[ci];
                        return JSON.stringify(linfo);
                    }
                }
            }
        } catch(e) {}
    }
    
    // Strategy 3: XPath text search for "下載" and its clickable parent
    try {
        var xpath = ".//text()[contains(., '下載') or contains(., '下载') or contains(., 'download')]";
        var result = document.evaluate(xpath, document.body, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
        for (var xi = 0; xi < result.snapshotLength; xi++) {
            var node = result.snapshotItem(xi);
            var el = node.parentElement;
            var orig = el;
            while (el && el !== document.body) {
                var tag = el.tagName.toLowerCase();
                var isClickable = (tag === 'button' || tag === 'a' || el.getAttribute('onclick') ||
                    el.getAttribute('role') === 'button' || (window.getComputedStyle(el).cursor === 'pointer'));
                if (isClickable && isVisible(el)) {
                    var xinfo = getRect(el);
                    xinfo.matchedBy = 'xpath:clickable';
                    return JSON.stringify(xinfo);
                }
                el = el.parentElement;
            }
            // No clickable parent found — use the immediate parent of the text node
            if (orig && isVisible(orig)) {
                var xinfo2 = getRect(orig);
                xinfo2.matchedBy = 'xpath:text-parent';
                return JSON.stringify(xinfo2);
            }
        }
    } catch(e) {}
    
    // Strategy 4: brute force by tree walker for text
    try {
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        var textNode;
        while (textNode = walker.nextNode()) {
            if (textNode.textContent.indexOf('下載') >= 0 || textNode.textContent.indexOf('下载') >= 0 ||
                textNode.textContent.indexOf('download') >= 0 || textNode.textContent.indexOf('Download') >= 0) {
                var p = textNode.parentElement;
                if (p && isVisible(p)) {
                    var pinfo = getRect(p);
                    pinfo.matchedBy = 'walker:text';
                    return JSON.stringify(pinfo);
                }
            }
        }
    } catch(e) {}
    
    // Strategy 5: find toolbar by known labels, get last/specific button
    try {
        var knownLabels = ['喜歡', '喜欢', '評論', '评论', '收藏', '觀看', '观看', '開始', '开始'];
        for (var li = 0; li < knownLabels.length; li++) {
            var labelEls = document.querySelectorAll('*');
            for (var lj = 0; lj < labelEls.length; lj++) {
                var lt = (labelEls[lj].textContent || '');
                if (lt.indexOf(knownLabels[li]) >= 0 && lt.length < 8) {
                    // Found a toolbar label, look for sibling buttons
                    var parent = labelEls[lj].parentElement;
                    if (parent) {
                        var siblings = parent.querySelectorAll('button, a, [onclick], [role="button"], > *');
                        var sibList = [];
                        for (var si = 0; si < siblings.length; si++) {
                            if (isVisible(siblings[si])) {
                                sibList.push(siblings[si]);
                            }
                        }
                        if (sibList.length >= 4) {
                            // Try the second-to-last or last button (often download is last)
                            for (var lastTry = sibList.length - 1; lastTry >= 0; lastTry--) {
                                var btn = sibList[lastTry];
                                var btnText = (btn.textContent || '').trim();
                                // Prefer text-less buttons (icons) or buttons with short text
                                if (btnText.length <= 3) {
                                    var binfo = getRect(btn);
                                    binfo.matchedBy = 'sibling:' + lastTry + '/' + sibList.length;
                                    return JSON.stringify(binfo);
                                }
                            }
                            // Fallback: just use the last visible sibling
                            var lastBtn = sibList[sibList.length - 1];
                            var linfo = getRect(lastBtn);
                            linfo.matchedBy = 'sibling-last';
                            return JSON.stringify(linfo);
                        }
                    }
                }
            }
        }
    } catch(e) {}
    
    // Strategy 6: find ALL visible buttons near page bottom, click the last one
    try {
        var allBtns = document.querySelectorAll('button, a, [onclick], [role="button"]');
        var bottomBtns = [];
        for (var bi = 0; bi < allBtns.length; bi++) {
            if (isVisible(allBtns[bi])) {
                var r = allBtns[bi].getBoundingClientRect();
                if (r.bottom > window.innerHeight * 0.6) {
                    bottomBtns.push(allBtns[bi]);
                }
            }
        }
        if (bottomBtns.length > 0) {
            bottomBtns.sort(function(a, b) {
                return b.getBoundingClientRect().bottom - a.getBoundingClientRect().bottom;
            });
            var binfo = getRect(bottomBtns[0]);
            binfo.matchedBy = 'bottom-btn:' + bottomBtns.length;
            return JSON.stringify(binfo);
        }
    } catch(e) {}
    
    // Dump debug info
    var allVis = [];
    var allEls = document.querySelectorAll('button, a, [onclick], [role="button"]');
    for (var di = 0; di < Math.min(allEls.length, 20); di++) {
        if (isVisible(allEls[di])) {
            var t = (allEls[di].textContent || '').trim();
            if (t.length <= 10) allVis.push(t);
        }
    }
    var snippet = (document.body.innerText || '').replace(/\\s+/g, ' ').substring(0, 100);
    return JSON.stringify({ error: 'NOT_FOUND', pageText: snippet, btns: allVis.slice(0, 8) });
})();"""

const val JS_CLICK_DOWNLOAD = """
(function() {
    function simulateTap(el) {
        if (!el) return false;
        el.scrollIntoView({ block: 'center', behavior: 'instant' });
        el.focus();
        
        var rect = el.getBoundingClientRect();
        var cx = rect.left + rect.width / 2;
        var cy = rect.top + rect.height / 2;
        
        var events = [];
        
        // touchstart
        try {
            var touchObj = new Touch({ identifier: Date.now(), target: el, clientX: cx, clientY: cy, radiusX: 2.5, radiusY: 2.5, force: 1 });
            var tsEvent = new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [touchObj], targetTouches: [touchObj], changedTouches: [touchObj] });
            el.dispatchEvent(tsEvent);
        } catch(e) {}
        
        // pointerdown + mousedown
        try {
            el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, pointerId: 1, isPrimary: true }));
        } catch(e) {}
        try {
            el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window }));
        } catch(e) {}
        
        // touchend
        try {
            var teEvent = new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], targetTouches: [], changedTouches: [touchObj] });
            el.dispatchEvent(teEvent);
        } catch(e) {}
        
        // pointerup + mouseup + click
        try {
            el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, pointerId: 1, isPrimary: true }));
        } catch(e) {}
        try {
            el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window }));
        } catch(e) {}
        try {
            el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window }));
        } catch(e) {}
        
        // fallback: native click
        el.click();
        return true;
    }
    
    // Strategy 1: find by text content
    var allElems = document.querySelectorAll('button, a, [role="button"], .btn, [class*="btn"], [class*="download"], [class*="Download"], [class*="down"]');
    var clicked = [];
    for (var i = 0; i < allElems.length; i++) {
        var el = allElems[i];
        var text = (el.textContent || '').trim();
        var aria = (el.getAttribute('aria-label') || '');
        var title = (el.getAttribute('title') || '');
        var combined = (text + ' ' + aria + ' ' + title).toLowerCase();
        if ((combined.indexOf('下载') >= 0 || combined.indexOf('download') >= 0 || combined.indexOf('下載') >= 0) &&
            combined.indexOf('上传') < 0 && combined.indexOf('upload') < 0) {
            simulateTap(el);
            var tag = el.tagName + '.' + (el.className || '').split(' ').slice(0,3).join('.');
            clicked.push(tag.substring(0, 20));
        }
    }
    
    // Strategy 2: find by icon (download icon SVG/path)
    if (clicked.length === 0) {
        var svgs = document.querySelectorAll('svg, [class*="icon"]');
        for (var j = 0; j < svgs.length; j++) {
            var parent = svgs[j].closest('button, a, [role="button"]');
            if (parent) {
                simulateTap(parent);
                clicked.push('svg-parent');
            }
        }
    }
    
    if (clicked.length > 0) {
        return 'CLICKED:' + clicked.length + '|' + clicked.join(',');
    }
    
    var bodyText = document.body.textContent || '';
    if (bodyText.indexOf('下载') >= 0 || bodyText.toLowerCase().indexOf('download') >= 0) {
        return 'FOUND_TEXT_BUT_NO_ELEM';
    }
    return 'NOT_FOUND';
})();
"""

const val JS_SOLVE_CAPTCHA = """
(function() {
    function simulateTap(el) {
        if (!el) return;
        var rect = el.getBoundingClientRect();
        var cx = rect.left + rect.width / 2;
        var cy = rect.top + rect.height / 2;
        try { el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy })); } catch(e) {}
        try { el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window })); } catch(e) {}
        try { el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy })); } catch(e) {}
        try { el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window })); } catch(e) {}
        try { el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window })); } catch(e) {}
        el.click();
    }

    var allText = document.body.innerText || '';
    var m = allText.match(/(\d+)\s*\+\s*(\d+)\s*=?\s*\??/);
    if (!m) return 'NO_MATH';
    var answer = parseInt(m[1]) + parseInt(m[2]);
    
    var inputs = document.querySelectorAll('input[type="text"], input[type="number"], input:not([type="hidden"]):not([type="submit"]):not([type="button"])');
    for (var j = 0; j < inputs.length; j++) {
        if (!inputs[j].value || inputs[j].value === '') {
            var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            nativeInputValueSetter.call(inputs[j], String(answer));
            inputs[j].dispatchEvent(new Event('input', { bubbles: true }));
            inputs[j].dispatchEvent(new Event('change', { bubbles: true }));
            break;
        }
    }
    
    var btns = document.querySelectorAll('button, input[type="submit"], [role="button"], .btn, [class*="confirm"], [class*="submit"], [class*="ok"]');
    for (var k = 0; k < btns.length; k++) {
        var t = (btns[k].textContent || '').trim();
        if (t.match(/确认|提交|确定|submit|ok|yes/i)) {
            simulateTap(btns[k]);
            return 'SOLVED:' + answer;
        }
    }
    if (btns.length > 0) { simulateTap(btns[0]); return 'SOLVED_CLICK:' + answer; }
    return 'SOLVED_NO_SUBMIT:' + answer;
})();
"""

val JS_CAPTURE_IMAGES = """
(function() {
    function findComicImages() {
        var candidates = [];
        var allImgs = document.querySelectorAll('img');
        for (var i = 0; i < allImgs.length; i++) {
            var img = allImgs[i];
            var src = img.src || img.getAttribute('data-src') || img.getAttribute('data-original') || '';
            if (src && (src.indexOf('media/photos') >= 0 || src.indexOf('.webp') >= 0 || src.indexOf('.jpg') >= 0 || src.indexOf('.png') >= 0)) {
                if (img.naturalWidth > 200 && img.naturalHeight > 200) {
                    candidates.push({ index: i, src: src, w: img.naturalWidth, h: img.naturalHeight });
                }
            }
        }
        return candidates;
    }
    
    var images = findComicImages();
    if (images.length === 0) {
        return JSON.stringify({ error: 'no_images', allImgCount: document.querySelectorAll('img').length });
    }
    
    var total = images.length;
    var batch = [];
    for (var idx = 0; idx < Math.min(total, 50); idx++) {
        batch.push({ index: idx, src: images[idx].src, w: images[idx].w, h: images[idx].h });
    }
    return JSON.stringify({ total: total, pages: batch, title: document.title || '' });
})();
"""

val JS_CAPTURE_SINGLE_IMAGE = """
(function() {
    var selector = arguments[0];
    var img = document.querySelector('img[src="' + selector + '"]') || 
              document.querySelector('img[data-src="' + selector + '"]') ||
              document.querySelector('img[data-original="' + selector + '"]');
    if (!img) {
        var all = document.querySelectorAll('img');
        for (var i = 0; i < all.length; i++) {
            if (all[i].src === selector || all[i].getAttribute('data-src') === selector || all[i].getAttribute('data-original') === selector) {
                img = all[i];
                break;
            }
        }
    }
    if (!img) return '';
    try {
        var canvas = document.createElement('canvas');
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        var ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0);
        return canvas.toDataURL('image/jpeg', 0.85);
    } catch(e) {
        return 'ERROR:' + e.message;
    }
})();
"""
