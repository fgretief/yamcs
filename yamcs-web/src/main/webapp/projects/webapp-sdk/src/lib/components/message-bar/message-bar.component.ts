import { AsyncPipe, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatIcon } from '@angular/material/icon';
import { MatToolbar, MatToolbarRow } from '@angular/material/toolbar';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { MessageService, SiteMessage } from '../../services/message.service';

@Component({
  standalone: true,
  selector: 'ya-message-bar',
  templateUrl: './message-bar.component.html',
  styleUrl: './message-bar.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncPipe,
    MatIcon,
    MatToolbar,
    MatToolbarRow,
    NgIf,
  ],
})
export class YaMessageBar {

  siteMessage$: Observable<SiteMessage | null>;
  show$: Observable<boolean>;

  constructor(private messageService: MessageService) {
    this.siteMessage$ = messageService.siteMessage$;
    this.show$ = this.siteMessage$.pipe(
      map(msg => !!msg)
    );
  }

  dismiss() {
    this.messageService.dismiss();
  }
}
