import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './core/api';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  readonly api = inject(Api);

  readonly username = signal('reader');
  readonly password = signal('reader-demo');
  readonly signingIn = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    const restore = this.api.restore();
    restore?.subscribe({
      error: () => this.api.signOut(),
    });
  }

  signIn(): void {
    this.signingIn.set(true);
    this.error.set(null);
    this.api.signIn(this.username(), this.password()).subscribe({
      next: () => this.signingIn.set(false),
      error: (err) => {
        this.signingIn.set(false);
        this.error.set(
          err.status === 401
            ? 'Incorrect username or password.'
            : `Cannot reach the backend (${err.status || 'network error'}).`,
        );
        this.api.signOut();
      },
    });
  }

  signOut(): void {
    this.api.signOut();
  }
}
