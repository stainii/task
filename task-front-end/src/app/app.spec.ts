import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';

import {App} from './app';
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from '@angular/material/icon';
import {MatListModule} from "@angular/material/list";
import {MatToolbarModule} from '@angular/material/toolbar';
import {MatSidenavModule} from '@angular/material/sidenav';

describe('App', () => {
  let component: App;
  let fixture: ComponentFixture<App>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [App],
      imports: [MatButtonModule, MatIconModule, MatListModule, MatSidenavModule, MatToolbarModule],
    });
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(App);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should compile', () => {
    expect(component).toBeTruthy();
  });
});
