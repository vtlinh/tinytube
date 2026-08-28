import { describe, it, expect } from 'vitest'
import {
  isValidChannelId,
  channelIdFromUrl,
  isChannelPage,
  isParentBrowsable,
  isAllowedAvatar,
  urlPath,
  parentBrowseUrl,
} from '../src/youtubeApi.js'

const ok = 'UC' + 'a'.repeat(22)

describe('isValidChannelId', () => {
  it('must be UC plus 22 url-safe characters', () => {
    expect(isValidChannelId(ok)).toBe(true)
    expect(isValidChannelId('UC_x-5XG1OV2P6uZZ5FSM9Tt')).toBe(true)
    for (const bad of [
      '',
      'UC',
      'UCshort',
      ok + 'a',
      ok.slice(0, -1),
      'XX' + 'a'.repeat(22),
      'UC' + 'a'.repeat(21) + '/',
      'UC' + 'a'.repeat(21) + '?',
      'uc' + 'a'.repeat(22),
    ]) {
      expect(isValidChannelId(bad), bad).toBe(false)
    }
  })
})

describe('channelIdFromUrl', () => {
  it('finds the channel id in a channel url', () => {
    expect(channelIdFromUrl(`https://www.youtube.com/channel/${ok}`)).toBe(ok)
    expect(channelIdFromUrl(`https://m.youtube.com/channel/${ok}/videos`)).toBe(ok)
    expect(channelIdFromUrl(`https://www.youtube.com/channel/${ok}?view=0`)).toBe(ok)
  })

  it('finds nothing where there is not an id', () => {
    for (const u of [
      'https://www.youtube.com/',
      'https://www.youtube.com/watch?v=aaaaaaaaaaa',
      'https://www.youtube.com/channel/notachannelid',
      'javascript:alert(1)',
      '',
    ]) {
      expect(channelIdFromUrl(u), u).toBeNull()
    }
  })

  it('an id in the query is not the page’s own', () => {
    expect(channelIdFromUrl(`https://m.youtube.com/@SomeChannel?u=/channel/${ok}`)).toBeNull()
    expect(channelIdFromUrl(`https://m.youtube.com/results?q=/channel/${ok}`)).toBeNull()
    expect(channelIdFromUrl(`https://m.youtube.com/@SomeChannel#/channel/${ok}`)).toBeNull()
    expect(channelIdFromUrl(`https://m.youtube.com/channel/${ok}?x=/@Someone`)).toBe(ok)
  })
})

describe('isChannelPage', () => {
  it('recognises a channel page', () => {
    for (const u of [
      `https://www.youtube.com/channel/${ok}`,
      `https://m.youtube.com/channel/${ok}/videos`,
      `https://www.youtube.com/channel/${ok}?view=0`,
      'https://www.youtube.com/@SomeChannel',
      'https://m.youtube.com/@SomeChannel/videos',
      'https://www.youtube.com/@some.channel/featured#x',
    ]) {
      expect(isChannelPage(u), u).toBe(true)
    }
  })

  it('refuses pages that only mention a channel', () => {
    for (const u of [
      'https://www.youtube.com/',
      'https://m.youtube.com/feed/trending',
      'https://www.youtube.com/watch?v=aaaaaaaaaaa',
      'https://www.youtube.com/results?search_query=@SomeChannel',
      'https://www.youtube.com/playlist?list=PL123',
      'https://www.youtube.com/shorts/aaaaaaaaaaa',
    ]) {
      expect(isChannelPage(u), u).toBe(false)
    }
  })

  it('the channel path must start the path', () => {
    expect(isChannelPage(`https://www.youtube.com/redirect?q=/channel/${ok}`)).toBe(false)
    expect(isChannelPage(`https://www.youtube.com/foo/channel/${ok}`)).toBe(false)
    expect(isChannelPage('https://www.youtube.com/foo/@SomeChannel')).toBe(false)
  })

  it('refuses channel-shaped paths on the wrong host', () => {
    expect(isChannelPage('https://youtube.com.attacker.example/@x')).toBe(false)
    expect(isChannelPage('https://i.ytimg.com/@SomeChannel')).toBe(false)
    expect(isChannelPage('https://www.youtube.com@attacker.example/@x')).toBe(false)
    expect(isChannelPage('javascript:/@x')).toBe(false)
    expect(isChannelPage('')).toBe(false)
  })
})

describe('urlPath', () => {
  it('drops query and fragment', () => {
    expect(urlPath('https://www.youtube.com/@x?a=1#b')).toBe('/@x')
    expect(urlPath('https://www.youtube.com')).toBe('/')
    expect(urlPath('https://www.youtube.com/?a=1')).toBe('/')
    expect(urlPath('javascript:alert(1)')).toBeNull()
  })
})

describe('isAllowedAvatar', () => {
  it('keeps avatars only from youtube’s own hosts', () => {
    expect(isAllowedAvatar('https://yt3.ggpht.com/x')).toBe(true)
    expect(isAllowedAvatar('https://yt3.googleusercontent.com/x')).toBe(true)
    for (const bad of [
      'https://attacker.example/x.jpg',
      'https://yt3.ggpht.com.attacker.example/x.jpg',
      'javascript:alert(1)',
      '',
    ]) {
      expect(isAllowedAvatar(bad), bad).toBe(false)
    }
  })
})

describe('isParentBrowsable', () => {
  it('is limited to youtube', () => {
    for (const u of [
      'https://m.youtube.com/',
      'https://www.youtube.com/@someone',
      'https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg',
      'https://rr1---sn-abc.googlevideo.com/videoplayback',
    ]) {
      expect(isParentBrowsable(u), u).toBe(true)
    }
    for (const u of [
      'https://youtube.com.attacker.example/',
      'https://notyoutube.com/',
      'https://www.youtube.com@attacker.example/',
      'https://evilgooglevideo.com/',
      'https://example.com/',
      'intent://x#Intent;end',
      'javascript:alert(1)',
    ]) {
      expect(isParentBrowsable(u), u).toBe(false)
    }
  })

  it('allows the google sign-in hosts', () => {
    for (const u of [
      'https://accounts.google.com/ServiceLogin?service=youtube',
      'https://accounts.youtube.com/accounts/CheckConnection',
      'https://consent.youtube.com/m?continue=https://www.youtube.com/',
      'https://apis.google.com/js/api.js',
      'https://ssl.gstatic.com/accounts/x.png',
      'https://lh3.googleusercontent.com/a/avatar',
      'https://google.com/',
      'https://ogs.google.com/widget/app/so',
      'https://play.google.com/log',
      'https://signaler-pa.clients6.google.com/punctual/v1/chooseServer',
    ]) {
      expect(isParentBrowsable(u), u).toBe(true)
    }
  })

  it('still refuses sign-in lookalikes', () => {
    for (const u of [
      'https://accounts.google.com.attacker.example/',
      'https://notgstatic.com/',
      'https://evilgoogleusercontent.com/',
      'https://accounts.google.com@attacker.example/',
    ]) {
      expect(isParentBrowsable(u), u).toBe(false)
    }
  })
})

describe('parentBrowseUrl', () => {
  it('keeps a mobile YouTube URL', () => {
    expect(parentBrowseUrl('https://m.youtube.com/')).toBe('https://m.youtube.com/')
  })

  it('turns a bare id or handle into a mobile channel page', () => {
    expect(parentBrowseUrl(ok)).toBe(`https://m.youtube.com/channel/${ok}`)
    expect(parentBrowseUrl('@SomeChannel')).toBe('https://m.youtube.com/@SomeChannel')
  })

  it('refuses anything off youtube', () => {
    expect(parentBrowseUrl('https://example.com/')).toBeNull()
    expect(parentBrowseUrl('example.com')).toBeNull()
    expect(parentBrowseUrl('javascript:alert(1)')).toBeNull()
    expect(parentBrowseUrl('')).toBeNull()
  })
})
