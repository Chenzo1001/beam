// Licensed under the Apache License, Version 2.0 (the 'License'); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

import * as React from 'react';

import { createRoot, Root } from 'react-dom/client';

import { act } from 'react';

import { InspectableList } from '../../inspector/InspectableList';

import { InspectableViewModel } from '../../inspector/InspectableViewModel';

const mockedInspectableViewModel = new InspectableViewModel({} as any);

let container: null | Element = null;
let root: Root | null = null;
beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  try {
    if (root) {
      await act(async () => {
        root.unmount();
        await new Promise(resolve => setTimeout(resolve, 0));
      });
    }
  } catch (error) {
    console.warn('During unmount:', error);
  } finally {
    if (container?.parentNode) {
      container.remove();
    }
    container = null;
    root = null;
  }
});

it('renders a list', async () => {
  await act(async () => {
    root.render(
      <InspectableList
        inspectableViewModel={mockedInspectableViewModel as any}
        id="pipeline_id"
        metadata={{
          name: 'pipeline_name',
          inMemoryId: 1,
          type: 'pipeline'
        }}
        pcolls={{
          pcoll1Id: {
            name: 'pcoll_1_name',
            inMemoryId: 2,
            type: 'pcollection'
          },
          pcoll2Id: {
            name: 'pcoll_2_name',
            inMemoryId: 3,
            type: 'pcollection'
          }
        }}
      />
    );
  });
  const listElement: Element = container.firstElementChild;
  const listHandle: Element = listElement.firstElementChild;
  expect(listHandle.tagName).toBe('DIV');
  expect(listHandle.getAttribute('class')).toContain(
    'rmwc-collapsible-list__handle'
  );
  const listHandleItem: Element = listHandle.firstElementChild;
  expect(listHandleItem.tagName).toBe('LI');
  expect(listHandleItem.getAttribute('class')).toContain('mdc-list-item');
  const listHandleText: Element = listHandleItem.children[2];
  expect(listHandleText.getAttribute('class')).toContain('mdc-list-item__text');
  const listHandlePrimaryText: Element = listHandleText.firstElementChild;
  expect(listHandlePrimaryText.getAttribute('class')).toContain(
    'mdc-list-item__primary-text'
  );
  expect(listHandlePrimaryText.textContent).toBe('pipeline_name');
  const listHandleMetaIcon: Element = listHandleItem.children[3];
  expect(listHandleMetaIcon.tagName).toBe('I');
  expect(listHandleMetaIcon.getAttribute('class')).toContain(
    'mdc-list-item__meta'
  );
  expect(listHandleMetaIcon.textContent).toBe('chevron_right');
  // Only check existence of collapsible list children because each child is an
  // individual list item that has its own unit tests.
  const listChildren: Element = listElement.children[1];
  expect(listChildren.tagName).toBe('DIV');
  expect(listChildren.getAttribute('class')).toContain(
    'rmwc-collapsible-list__children'
  );
  const listChildItems: HTMLCollection =
    listChildren.firstElementChild.children;
  expect(listChildItems).toHaveLength(2);
});
