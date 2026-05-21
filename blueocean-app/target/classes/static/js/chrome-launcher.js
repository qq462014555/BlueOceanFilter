/**
 * Chrome 调试浏览器启动工具函数
 * 所有页面统一调用此文件，新增端口只需加配置
 *
 * 使用方式：
 *   ChromeLauncher.init({ port: 9222, label: '采集', url: 'https://xxx.com' });
 *   ChromeLauncher.init({ port: 9223, label: '千牛', url: 'https://myseller.taobao.com' });
 *   ChromeLauncher.init({ port: 9224, label: '淘宝', url: 'https://sxkc.wusetech.com/' });
 */
const ChromeLauncher = (() => {
    let config = {};

    return {
        init(options) {
            config = Object.assign({
                port: 9222,
                label: '采集',
                url: null,
                buttonId: null,
                defaultText: '启动Chrome'
            }, options);
        },

        get defaultText() {
            return `启动Chrome(${config.port}-${config.label})`;
        },

        async launch() {
            const btn = config.buttonId ? document.getElementById(config.buttonId) : null;
            if (btn) {
                btn.disabled = true;
                btn.textContent = '启动中...';
            }
            try {
                const resp = await fetch(`/api/scraper/launch-chrome/${config.port}`, { method: 'POST' });
                const data = await resp.json();
                if (!resp.ok) {
                    showToast(data.message || '启动失败', 'error');
                    return;
                }
                showToast('Chrome 已启动（端口 ' + config.port + '）');
                if (config.url) {
                    window.open(config.url, '_blank');
                }
            } catch (e) {
                showToast('启动失败: ' + e.message, 'error');
            } finally {
                if (btn) {
                    btn.disabled = false;
                    btn.textContent = this.defaultText;
                }
            }
        }
    };
})();

/**
 * 快速初始化，链式调用
 * 例如：ChromeLauncher.quick(9222, '采集', 'https://xxx.com', 'chromeBtn');
 */
function quickLaunch(port, label, url, btnId) {
    ChromeLauncher.init({ port, label, url, buttonId: btnId });
    ChromeLauncher.launch();
}

/**
 * Toast 提示（兼容各页面已有的 showToast 或内联实现）
 */
function showToast(msg, type = 'success') {
    let el = document.getElementById('chrome-toast');
    if (el) el.remove();
    el = document.createElement('div');
    el.id = 'chrome-toast';
    el.className = 'chrome-toast ' + type;
    el.textContent = msg;
    if (!document.getElementById('chrome-toast-style')) {
        const style = document.createElement('style');
        style.id = 'chrome-toast-style';
        style.textContent = `.chrome-toast{position:fixed;top:24px;right:24px;padding:10px 20px;background:#fff;border-radius:6px;box-shadow:0 4px 16px rgba(0,0,0,0.12);z-index:9999;font-size:13px;animation:toastSlide 0.2s ease}.chrome-toast.success{border-left:3px solid #52c41a}.chrome-toast.error{border-left:3px solid #ff4d4f}@keyframes toastSlide{from{transform:translateX(100%);opacity:0}to{transform:translateX(0);opacity:1}}`;
        document.head.appendChild(style);
    }
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2500);
}
