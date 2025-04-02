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

import { InteractiveInspector } from '../../inspector/InteractiveInspector';

import { InspectableViewModel } from '../../inspector/InspectableViewModel';

const fakeSessionContext = {
  session: {
    kernel: {
      requestExecute: function (): Record<string, unknown> {
        return {
          onIOPub: function (): void {
            // do nothing
          }
        };
      }
    }
  },
  kernelDisplayName: ''
};

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

it('renders the top app bar and drawer wrapped inspectables', async () => {
  const inspectableViewModel = new InspectableViewModel(
    fakeSessionContext as any
  );
  await act(async () => {
    root.render(
      <InteractiveInspector
        sessionContext={fakeSessionContext as any}
        inspectableViewModel={inspectableViewModel}
      />
    );
  });
  const topAppBarHeader: Element = container.firstElementChild;
  expect(topAppBarHeader.tagName).toBe('HEADER');
  expect(topAppBarHeader.getAttribute('class')).toContain('mdc-top-app-bar');
  expect(topAppBarHeader.getAttribute('class')).toContain(
    'mdc-top-app-bar--fixed'
  );
  expect(topAppBarHeader.getAttribute('class')).toContain(
    'mdc-top-app-bar--dense'
  );
  expect(topAppBarHeader.innerHTML).toContain('menu');
  expect(topAppBarHeader.innerHTML).toContain('Inspector [kernel:no kernel]');
  const topAppBarFixedAdjust: Element = container.children[1];
  expect(topAppBarFixedAdjust.tagName).toBe('DIV');
  expect(topAppBarFixedAdjust.getAttribute('class')).toContain(
    'mdc-top-app-bar--fixed-adjust'
  );
  const interactiveInspectorDiv: Element = container.children[2];
  expect(interactiveInspectorDiv.tagName).toBe('DIV');
  expect(interactiveInspectorDiv.getAttribute('class')).toContain(
    'InteractiveInspector'
  );
  const inspectablesAside: Element = interactiveInspectorDiv.firstElementChild;
  expect(inspectablesAside.tagName).toBe('ASIDE');
  expect(inspectablesAside.innerHTML).toContain(
    '<div>No inspectable pipeline nor pcollection has been defined.</div>'
  );
  expect(inspectablesAside.firstElementChild.getAttribute('class')).toContain(
    'mdc-drawer__content'
  );
  const inspectableViewAsAppContent: Element =
    interactiveInspectorDiv.children[1];
  expect(inspectableViewAsAppContent.tagName).toBe('DIV');
  expect(inspectableViewAsAppContent.getAttribute('class')).toContain(
    'mdc-drawer-app-content'
  );
  expect(
    inspectableViewAsAppContent.firstElementChild.getAttribute('class')
  ).toContain('InspectableView');
});

it('renders the drawer open by default', async () => {
  const inspectableViewModel = new InspectableViewModel(
    fakeSessionContext as any
  );
  await act(async () => {
    root.render(
      <InteractiveInspector
        sessionContext={fakeSessionContext as any}
        inspectableViewModel={inspectableViewModel}
      />
    );
  });
  const inspectablesAside: Element = container.children[2].firstElementChild;
  expect(inspectablesAside.getAttribute('class')).toContain('mdc-drawer--open');
});

it('closes the drawer on flip from open state', async () => {
  const inspectorRef: React.RefObject<InteractiveInspector> =
    React.createRef<InteractiveInspector>();
  const inspectableViewModel = new InspectableViewModel(
    fakeSessionContext as any
  );
  await act(async () => {
    root.render(
      <InteractiveInspector
        ref={inspectorRef}
        sessionContext={fakeSessionContext as any}
        inspectableViewModel={inspectableViewModel}
      />
    );
  });

  await act(async () => {
    const inspector = inspectorRef.current;
    if (inspector) {
      inspector.flipDrawer();
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  });

  // react test renderer does not re-render the drawer component even if the
  // state is changed. Test the state change instead of DOM change.
  await act(async () => {
    const inspector = inspectorRef.current;
    if (inspector) {
      expect(inspector.state.drawerOpen).toBe(false);
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  });
});

it('updates session info on change', async () => {
  const inspectorRef: React.RefObject<InteractiveInspector> =
    React.createRef<InteractiveInspector>();
  const inspectableViewModel = new InspectableViewModel(
    fakeSessionContext as any
  );

  await act(async () => {
    root.render(
      <InteractiveInspector
        ref={inspectorRef}
        sessionContext={fakeSessionContext as any}
        inspectableViewModel={inspectableViewModel}
      />
    );
  });

  await act(async () => {
    const inspector = inspectorRef.current;
    if (inspector) {
      fakeSessionContext.kernelDisplayName = 'new kernel';
      inspector.updateSessionInfo();
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  });

  const topAppBarHeader: Element = container.firstElementChild.firstElementChild.firstElementChild.children[1];
  expect(topAppBarHeader.innerHTML).toContain('Inspector [kernel:new kernel]');
});
