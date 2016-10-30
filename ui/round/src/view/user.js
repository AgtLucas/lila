var m = require('mithril');
var game = require('game').game;

function ratingDiff(player) {
  if (typeof player.ratingDiff === 'undefined') return null;
  if (player.ratingDiff === 0) return m('span.rp.null', '±0');
  if (player.ratingDiff > 0) return m('span.rp.up', '+' + player.ratingDiff);
  if (player.ratingDiff < 0) return m('span.rp.down', player.ratingDiff);
}

function relayUser(player, klass) {
  return m('span', {
    class: 'text ' + klass,
    'data-icon': '8'
  }, [
    (player.title ? player.title + ' ' : '') + player.name,
    player.rating ? ' (' + player.rating + ')' : ''
  ]);
}

function aiName(ctrl, player) {
  var name = ctrl.data.game.variant.key === 'crazyhouse' ? 'Sunsetter' : 'Stockfish';
  return ctrl.trans('aiNameLevelAiLevel', name, player.ai);
}

module.exports = {
  userHtml: function(ctrl, player, klass) {
    var d = ctrl.data;
    var user = player.user;
    if (d.relay) return relayUser(d.relay[player.color], klass);
    var perf = user ? user.perfs[d.game.perf] : null;
    var rating = player.rating ? player.rating : (perf ? perf.rating : null);
    var fullName = (user.title ? user.title + ' ' : '') + user.username;
    var connecting = !player.onGame && ctrl.vm.firstSeconds && user.online;
    return user ? [
      m('a', {
        class: 'text ulpt user_link ' +
          (player.onGame ? 'online' : 'offline') +
          (klass ? ' ' + klass : '') +
          (fullName.length > 20 ? ' long' : '') +
          (connecting ? ' connecting' : ''),
        href: '/@/' + user.username,
        target: game.isPlayerPlaying(d) ? '_blank' : '_self'
      }, [
        m('i', {
          class: 'line' + (user.patron ? ' patron' : ''),
          'title': connecting ? 'Connecting to the game' : (player.onGame ? 'Joined the game' : 'Left the game')
        }),
        m('name', fullName),
        rating ? m('rating', rating + (player.provisional ? '?' : '')) : null,
        ratingDiff(player),
        player.engine ? m('span[data-icon=j]', {
          title: ctrl.trans('thisPlayerUsesChessComputerAssistance')
        }) : null
      ]),
      // playerOnGameIcon
    ] : m('span.user_link', [
      player.name || 'Anonymous',
      d.game.source == 'relay' ? null : playerOnGameIcon
    ]);
  },
  userTxt: function(ctrl, player) {
    if (player.user) {
      var perf = player.user.perfs[ctrl.data.game.perf];
      var name = (player.user.title ? player.user.title + ' ' : '') + player.user.username;
      var rating = player.rating ? player.rating : (perf ? perf.rating : null);
      rating = rating ? ' (' + rating + (player.provisional ? '?' : '') + ')' : '';
      return name + rating;
    } else if (player.ai) return aiName(ctrl, player)
    else return 'Anonymous';
  },
  aiName: aiName
};
