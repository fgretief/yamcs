import { Injectable } from '@angular/core';
import { YamcsClient, InstanceClient, Instance, TimeInfo } from '@yamcs/client';
import { BehaviorSubject } from 'rxjs/BehaviorSubject';
import { Subscription } from 'rxjs/Subscription';


/**
 * Singleton service for facilitating working with a websocket connection
 * to a specific instance.
 */
@Injectable()
export class YamcsService {

  readonly yamcsClient = new YamcsClient();
  readonly instance$ = new BehaviorSubject<Instance | null>(null);

  private selectedInstance: InstanceClient;

  private timeInfo$ = new BehaviorSubject<TimeInfo | null>(null);
  private timeInfoSubscription: Subscription;

  /**
   * Prepares a (new) instance.
   */
  switchInstance(instance: Instance) {
    return new Promise<void>((resolve, reject) => {
      if (this.selectedInstance) {
        if (this.selectedInstance.instance === instance.name) {
          resolve();
          return;
        } else {
          this.timeInfo$.next(null);
          this.timeInfoSubscription.unsubscribe();
          this.selectedInstance.closeConnection();
        }
      }

      this.instance$.next(instance);
      this.selectedInstance = this.yamcsClient.createInstanceClient(instance.name);

      // Listen to time updates, so that we can easily provide actual mission time to components
      this.selectedInstance.getTimeUpdates().then(response => {
        this.timeInfo$.next(response.timeInfo);
        this.timeInfoSubscription = response.timeInfo$.subscribe(timeInfo => {
          this.timeInfo$.next(timeInfo);
        });
        resolve();
      }).catch(err => {
        reject(err);
      });
    });
  }

  /**
   * Returns the currently active instance (if any).
   */
  getInstance() {
    return this.instance$.getValue()!;
  }

  /**
   * Returns the InstanceClient for the currently active instance (if any).
   */
  getInstanceClient() {
    return this.selectedInstance;
  }

  /**
   * Returns latest mission time for the currently active instance (if any).
   */
  getMissionTime() {
    return new Date(Date.parse(this.timeInfo$.getValue()!.currentTimeUTC));
  }
}