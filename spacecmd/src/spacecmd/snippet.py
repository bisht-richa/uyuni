#
# Licensed under the GNU General Public License Version 3
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program; if not, write to the Free Software
# Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Copyright 2013 Aron Parsons <aronparsons@gmail.com>
#

# NOTE: the 'self' variable is an instance of SpacewalkShell

# wildcard import
# pylint: disable=W0401,W0614

# unused argument
# pylint: disable=W0613

# invalid function name
# pylint: disable=C0103

import gettext
from spacecmd.i18n import _N
from spacecmd.utils import *

translation = gettext.translation('spacecmd', fallback=True)
try:
    _ = translation.ugettext
except AttributeError:
    _ = translation.gettext

def help_snippet_list(self):
    print(_('snippet_list: List the available Kickstart snippets'))
    print(_('usage: snippet_list'))


def do_snippet_list(self, args, doreturn=False):
    snippets = self.client.kickstart.snippet.listCustom(self.session)
    snippets = sorted([s.get('name') for s in snippets])

    if doreturn:
        return snippets
    if snippets:
        print('\n'.join(snippets))

    return None

####################


def help_snippet_details(self):
    print(_('snippet_details: Show the contents of a snippet'))
    print(_('usage: snippet_details SNIPPET ...'))


def complete_snippet_details(self, text, line, beg, end):
    return tab_completer(self.do_snippet_list('', True),
                         text)


def do_snippet_details(self, args):
    arg_parser = get_argument_parser()

    (args, _options) = parse_command_arguments(args, arg_parser)

    if not args:
        self.help_snippet_details()
        return 1

    add_separator = False

    snippets = self.client.kickstart.snippet.listCustom(self.session)

    snippet = None
    for name in args:
        for s in snippets:
            if s.get('name') == name:
                snippet = s
                break

        if not snippet:
            logging.warning(_N('%s is not a valid snippet') % name)
            continue

        if add_separator:
            print(self.SEPARATOR)
        add_separator = True

        print(_('Name:   %s') % snippet.get('name'))
        print(_('Macro:  %s') % snippet.get('fragment'))
        print(_('File:   %s') % snippet.get('file'))

        print('')
        print(snippet.get('contents'))

    return 0

####################


def help_snippet_create(self):
    print(_('snippet_create: Create a Kickstart snippet'))
    print(_('''usage: snippet_create [options])

options:
  -n NAME
  -f FILE'''))


def do_snippet_create(self, args, update_name=''):
    arg_parser = get_argument_parser()
    arg_parser.add_argument('-n', '--name')
    arg_parser.add_argument('-f', '--file')

    (args, options) = parse_command_arguments(args, arg_parser)

    contents = ''

    if is_interactive(options):
        # if update_name was passed, we're trying to update an existing snippet
        if update_name:
            options.name = update_name

            snippets = self.client.kickstart.snippet.listCustom(self.session)
            for s in snippets:
                if s.get('name') == update_name:
                    contents = s.get('contents')
                    break

        if not options.name:
            options.name = prompt_user('Name:', noblank=True)

        if self.user_confirm(_('Read an existing file [y/N]:'),
                             nospacer=True, ignore_yes=True):
            options.file = prompt_user('File:')
        else:
            (contents, _ignore) = editor(template=contents,
                                         delete=True)
    else:
        if not options.name:
            logging.error(_N('A name is required for the snippet'))
            return 1

        if not options.file:
            logging.error(_N('A file is required'))
            return 1

    if options.file:
        contents = read_file(options.file)

    print('')
    print(_('Snippet: %s') % options.name)
    print(_('Contents'))
    print('--------')
    print(contents)

    if self.user_confirm():
        self.client.kickstart.snippet.createOrUpdate(self.session,
                                                     options.name,
                                                     contents)

    return 0

####################


def help_snippet_update(self):
    print(_('snippet_update: Update a Kickstart snippet'))
    print(_('usage: snippet_update NAME'))


def complete_snippet_update(self, text, line, beg, end):
    return tab_completer(self.do_snippet_list('', True), text)


def do_snippet_update(self, args):
    arg_parser = get_argument_parser()

    (args, _options) = parse_command_arguments(args, arg_parser)

    if not args:
        self.help_snippet_update()
        return 1

    return self.do_snippet_create('', update_name=args[0])

####################


def help_snippet_delete(self):
    print(_('snippet_delete: Delete a Kickstart snippet'))
    print(_('usage: snippet_delete NAME'))


def complete_snippet_delete(self, text, line, beg, end):
    return tab_completer(self.do_snippet_list('', True), text)


def do_snippet_delete(self, args):
    arg_parser = get_argument_parser()

    (args, _options) = parse_command_arguments(args, arg_parser)

    if not args:
        self.help_snippet_delete()
        return 1

    snippet = args[0]

    if self.user_confirm(_('Remove this snippet [y/N]:')):
        self.client.kickstart.snippet.delete(self.session, snippet)
        return 0
    else:
        return 1
