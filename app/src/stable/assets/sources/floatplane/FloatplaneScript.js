'use strict';

const PLATFORM = 'Floatplane';
const BASE = 'https://www.floatplane.com';
const API = `${BASE}/api/v3`;
const PAGE_SIZE = 20;
const POST_RE = /^https:\/\/(?:www\.)?floatplane\.com\/post\/([A-Za-z0-9]{1,32})$/;
const CHANNEL_RE = /^https:\/\/(?:www\.)?floatplane\.com\/channel\/([A-Za-z0-9_-]{1,64})(?:\/home)?$/;
// Host of an https URL, with no userinfo or other tricks before the path.
const MEDIA_RE = /^https:\/\/([A-Za-z0-9.-]+)(?::\d{1,5})?(?:[/?#]|$)/;

let config = {};

source.enable = function (conf) {
  config = conf;
};

source.isContentDetailsUrl = (url) => typeof url === 'string' && POST_RE.test(url);
source.isChannelUrl = (url) => typeof url === 'string' && CHANNEL_RE.test(url);

source.getHome = function () {
  requireLogin();
  const ids = subscribedCreators();
  if (!ids.length) return new ContentPager([], false);
  return new HomePager(ids);
};

// Floatplane has no global search; search each subscribed creator.
source.search = function (query) {
  requireLogin();
  const q = encodeURIComponent(text(query));
  const items = eachCreator((id) => list(api(`/content/creator?id=${encodeURIComponent(id)}&limit=${PAGE_SIZE}&fetchAfter=0&hasVideo=true&search=${q}`, 'search')))
    .flat()
    .map(toVideo)
    .filter(Boolean)
    .sort((a, b) => b.datetime - a.datetime);
  return new ContentPager(items, false);
};

source.getChannel = function (url) {
  requireLogin();
  const c = creatorByUrl(url);
  return new PlatformChannel({
    id: pid(c.id),
    name: text(c.title),
    thumbnail: imagePath(c.icon),
    banner: imagePath(c.cover),
    subscribers: 0,
    description: text(c.description),
    url: channelUrl(c.urlname),
    urlAlternatives: [],
    links: {},
  });
};

source.getChannelContents = function (url) {
  requireLogin();
  return new CreatorPager(text(creatorByUrl(url).id));
};

source.getUserSubscriptions = function () {
  requireLogin();
  return eachCreator((id) => channelUrl(api(`/creator/info?id=${encodeURIComponent(id)}`, 'creator').urlname)).filter(Boolean);
};

source.getContentDetails = function (url) {
  requireLogin();
  const id = parse(url, POST_RE);
  const post = api(`/content/post?id=${encodeURIComponent(id)}`, 'post');
  if (post.isAccessible === false) {
    throw new ScriptException(`This post is locked. Subscribe to ${text(post.creator && post.creator.title) || 'its creator'} on floatplane.com to watch it.`);
  }
  const first = list(post.videoAttachments)[0];
  const videoId = text(first && typeof first === 'object' ? first.id : first);
  if (!/^[A-Za-z0-9]{1,32}$/.test(videoId)) throw new ScriptException('This Floatplane post has no video');

  const delivery = api(`/delivery/info?scenario=onDemand&entityId=${encodeURIComponent(videoId)}&outputKind=hls.fmp4`, 'video delivery');
  const duration = seconds(post.metadata && post.metadata.videoDuration);
  // The player fetches with the session (for HLS keys); the script never touches the cookie.
  const requestModifier = { options: { applyAuthClient: http.getDefaultClient(true).clientId } };
  const sources = list(delivery.groups).flatMap((g) => list(g && g.variants)
    .filter((v) => v && v.enabled !== false && v.hidden !== true)
    .map((v) => ({ name: text(v.label), url: mediaUrl(v, g) }))
    .filter((v) => v.url)
    .map((v) => new HLSSource({ ...v, duration, priority: false, requestModifier })));
  if (!sources.length) throw new ScriptException('Floatplane returned no playable stream for this video');

  return new PlatformVideoDetails({
    ...videoFields(post),
    id: pid(id),
    url: postUrl(id),
    description: text(post.text),
    rating: new RatingLikesDislikes(Number(post.likes) || 0, Number(post.dislikes) || 0),
    video: new VideoSourceDescriptor(sources),
    subtitles: [],
  });
};

class HomePager extends ContentPager {
  constructor(ids) {
    const page = homePage(ids, []);
    super(page.items, page.hasMore, { ids, after: page.after });
  }

  nextPage() {
    const page = homePage(this.context.ids, this.context.after);
    this.context.after = page.after;
    this.results = page.items;
    this.hasMore = page.hasMore;
    return this;
  }
}

function homePage(ids, after) {
  const q = ids.map((id, i) => `ids[${i}]=${encodeURIComponent(id)}`);
  q.push(`limit=${PAGE_SIZE}`);
  after.forEach((e, i) => {
    for (const k of ['creatorId', 'blogPostId', 'moreFetchable']) q.push(`fetchAfter[${i}][${k}]=${encodeURIComponent(text(e[k]))}`);
  });
  const res = api(`/content/creator/list?${q.join('&')}`, 'home feed');
  const lastElements = list(res.lastElements).filter((e) => e && typeof e === 'object');
  return {
    items: list(res.blogPosts).map(toVideo).filter(Boolean),
    after: lastElements,
    hasMore: lastElements.some((e) => e.moreFetchable === true),
  };
}

class CreatorPager extends ContentPager {
  constructor(id) {
    const page = creatorPage(id, 0);
    super(page.items, page.hasMore, { id, offset: PAGE_SIZE });
  }

  nextPage() {
    const page = creatorPage(this.context.id, this.context.offset);
    this.context.offset += PAGE_SIZE;
    this.results = page.items;
    this.hasMore = page.hasMore;
    return this;
  }
}

function creatorPage(id, offset) {
  const raw = list(api(`/content/creator?id=${encodeURIComponent(id)}&limit=${PAGE_SIZE}&fetchAfter=${offset}&hasVideo=true`, 'channel'));
  return { items: raw.map(toVideo).filter(Boolean), hasMore: raw.length >= PAGE_SIZE };
}

function requireLogin() {
  if (!bridge.isLoggedIn()) throw new LoginRequiredException('Log in to Floatplane to watch the creators you subscribe to');
}

function parse(url, re) {
  const match = typeof url === 'string' && re.exec(url);
  if (!match) throw new ScriptException('Not a Floatplane link');
  return match[1];
}

// Errors name the step only: URLs may carry delivery tokens and bodies are untrusted.
function api(path, what) {
  const res = http.GET(API + path, { Accept: 'application/json', 'User-Agent': 'Grayjay-Floatplane/1' }, true);
  if (res.code === 401) throw new LoginRequiredException('Your Floatplane session has expired. Log in to Floatplane again.');
  // Cloudflare blocks also answer 403, so don't promise that logging in fixes it.
  if (res.code === 403) {
    throw new LoginRequiredException('Floatplane refused the request (HTTP 403). Your session may have expired: log in again. If that does not help, Floatplane may be blocking the app.');
  }
  if (!res.isOk) throw new ScriptException(`Floatplane ${what} request failed (${res.code})`);
  let json = null;
  try { json = JSON.parse(res.body); } catch (e) { /* handled below */ }
  if (!json || typeof json !== 'object') throw new ScriptException(`Floatplane ${what} returned an invalid response`);
  return json;
}

// One creator can appear once per plan.
function subscribedCreators() {
  const ids = list(api('/user/subscriptions', 'subscriptions')).map((s) => text(s && s.creator)).filter(Boolean);
  return [...new Set(ids)];
}

// Runs fn per subscribed creator. One failing creator doesn't hide the rest;
// login errors, and every creator failing, still reach the user.
function eachCreator(fn) {
  let error = null;
  const out = subscribedCreators().flatMap((id) => {
    try { return [fn(id)]; } catch (e) {
      if (e instanceof LoginRequiredException) throw e;
      error = error || e;
      return [];
    }
  });
  if (error && !out.length) throw error;
  return out;
}

function creatorByUrl(url) {
  const name = parse(url, CHANNEL_RE);
  const c = list(api(`/creator/named?creatorURL[0]=${encodeURIComponent(name)}`, 'creator'))[0];
  if (!c || typeof c !== 'object' || !c.id) throw new ScriptException('Floatplane creator not found. Check the link, or subscribe to this creator on floatplane.com.');
  return c;
}

// The player carries the session, so it may only be pointed at Floatplane's own https hosts.
function mediaUrl(v, g) {
  let url = text(v.url);
  if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) {
    const origin = list(v.origins)[0] || list(g.origins)[0];
    if (!origin || !url) return '';
    url = text(origin.url).replace(/\/+$/, '') + (url.startsWith('/') ? url : `/${url}`);
  }
  const m = MEDIA_RE.exec(url);
  const host = m ? m[1].toLowerCase() : '';
  return host === 'floatplane.com' || host.endsWith('.floatplane.com') ? url : '';
}

function toVideo(p) {
  if (!p || p.isAccessible === false || !(p.metadata && p.metadata.hasVideo === true)) return null;
  if (!/^[A-Za-z0-9]{1,32}$/.test(text(p.id))) return null;
  return new PlatformVideo(videoFields(p));
}

function videoFields(p) {
  const c = p.creator && typeof p.creator === 'object' ? p.creator : {};
  return {
    id: pid(p.id),
    name: text(p.title),
    thumbnails: thumbnails(p.thumbnail),
    author: new PlatformAuthorLink(pid(c.id), text(c.title), channelUrl(c.urlname), imagePath(c.icon)),
    datetime: Math.floor((Date.parse(p.releaseDate) || 0) / 1000),
    url: postUrl(p.id),
    duration: seconds(p.metadata && p.metadata.videoDuration),
    viewCount: 0,
    isLive: false,
  };
}

function thumbnails(t) {
  const images = t && typeof t === 'object' ? [t, ...list(t.childImages)] : [];
  return new Thumbnails(images
    .filter((i) => i && typeof i.path === 'string' && i.path)
    .map((i) => new Thumbnail(i.path, Number(i.height) || 0)));
}

const pid = (id) => new PlatformID(PLATFORM, text(id), config.id);
const postUrl = (id) => `${BASE}/post/${id}`;
const channelUrl = (name) => (/^[A-Za-z0-9_-]{1,64}$/.test(text(name)) ? `${BASE}/channel/${name}/home` : '');
const imagePath = (img) => (img && typeof img.path === 'string' ? img.path : '');
const list = (x) => (Array.isArray(x) ? x : []);
const text = (x) => (x == null ? '' : String(x));
const seconds = (s) => Math.round(Number(s) || 0);
