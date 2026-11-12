((checkContent, checkCountdown) => {
    if (!['https://www.rngdle.com', 'https://rngdle.com'].includes(location.origin)) return null;
    const visible = node => node && node.getClientRects().length > 0 &&
        getComputedStyle(node).visibility === 'visible';
    const header = document.querySelector('header');
    const profile = header && header.querySelector('a[href="/profile"][aria-label^="Profile:"]');
    let account = 'SIGNED_IN';
    if (!profile) {
        const signIn = header && [...header.querySelectorAll('button')].some(button =>
            visible(button) && /^sign in$/i.test(button.textContent.trim()));
        const email = [...document.querySelectorAll('input[type="email"]')].some(input => {
            const form = input.closest('form');
            return visible(input) && form && [...form.querySelectorAll('button')].some(button =>
                visible(button) && /^send magic link$/i.test(button.textContent.trim()));
        });
        const mailSent = [...document.querySelectorAll('p')].some(node =>
            visible(node) && /^check your email$/i.test(node.textContent.trim()));
        account = mailSent ? 'MAIL_SENT' : email ? 'EMAIL_FORM' : signIn ? 'SIGNED_OUT' : 'UNKNOWN';
    }

    let content = false;
    if (checkContent) {
        const main = document.querySelector('main');
        const text = visible(main) ? main.innerText.trim() : '';
        content = (text.length > 0 && !/^loading[.…\s]*$/i.test(text)) ||
            (!main && document.readyState === 'complete' && visible(document.querySelector('h1')));
    }

    let countdown = null;
    if (checkCountdown && location.pathname === '/' && !location.search && !location.hash) {
        for (const label of document.querySelectorAll('span')) {
            if (label.children.length || label.textContent.trim() !== 'Next roll in') continue;
            const value = label.nextElementSibling;
            if (visible(label) && visible(value)) {
                countdown = value.textContent.trim();
                break;
            }
        }
    }
    return {content, account, countdown};
})
