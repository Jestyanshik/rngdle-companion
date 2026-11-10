(() => {
    if (!['https://www.rngdle.com', 'https://rngdle.com'].includes(location.origin)) return false;
    const visible = node => node && node.getClientRects().length > 0 &&
        getComputedStyle(node).visibility === 'visible';
    if ([...document.querySelectorAll('input[type="email"]')].some(visible)) return true;
    const button = [...document.querySelectorAll('header button')].find(node =>
        visible(node) && /^sign in$/i.test(node.textContent.trim()));
    if (!button) return false;
    button.click();
    return true;
})()
