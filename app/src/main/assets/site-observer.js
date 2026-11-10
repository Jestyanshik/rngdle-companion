(() => {
    if (!['https://www.rngdle.com', 'https://rngdle.com'].includes(location.origin)) return null;
    const visible = node => node && node.getClientRects().length > 0 &&
        getComputedStyle(node).visibility === 'visible';
    const header = document.querySelector('header');
    const signIn = header && [...header.querySelectorAll('button')].some(button =>
        visible(button) && /^sign in$/i.test(button.textContent.trim()));
    const profile = header && header.querySelector('a[href="/profile"][aria-label^="Profile:"]');
    const email = [...document.querySelectorAll('form')].some(form =>
        visible(form.querySelector('input[type="email"]')) && [...form.querySelectorAll('button')].some(button =>
            visible(button) && /^send magic link$/i.test(button.textContent.trim())));
    const mailSent = [...document.querySelectorAll('p')].some(node =>
        visible(node) && /^check your email$/i.test(node.textContent.trim()));
    const account = profile ? 'SIGNED_IN' : mailSent ? 'MAIL_SENT' : email ? 'EMAIL_FORM' :
        signIn ? 'SIGNED_OUT' : 'UNKNOWN';
    const main = document.querySelector('main');
    const text = visible(main) ? main.innerText.trim() : '';
    const content = (text.length > 0 && !/^loading[.…\s]*$/i.test(text)) ||
        (!main && document.readyState === 'complete' && visible(document.querySelector('h1')));
    return {content, account};
})()
