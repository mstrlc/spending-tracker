// A few stroke icons drawn for this app (24px grid, 1.75 stroke), so chrome needs no icon dependency.
import type { SVGProps } from "react";

function Icon({ children, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...props}
    >
      {children}
    </svg>
  );
}

export const ChevronLeft = () => (
  <Icon>
    <path d="M15 6l-6 6 6 6" />
  </Icon>
);

export const ChevronRight = () => (
  <Icon>
    <path d="M9 6l6 6-6 6" />
  </Icon>
);

export const Plus = () => (
  <Icon>
    <path d="M12 5v14M5 12h14" />
  </Icon>
);

export const Close = () => (
  <Icon>
    <path d="M6 6l12 12M18 6L6 18" />
  </Icon>
);

export const Receipt = () => (
  <Icon>
    <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
    <path d="M9 8h6M9 12h6" />
  </Icon>
);

export const Inbound = () => (
  <Icon>
    <path d="M12 3v11M7.5 9.5L12 14l4.5-4.5" />
    <path d="M4 14v5h16v-5" />
  </Icon>
);

export const Wallet = () => (
  <Icon>
    <rect x="3" y="6" width="18" height="13" rx="2" />
    <path d="M3 10h18M16 14.5h2" />
  </Icon>
);

export const Tag = () => (
  <Icon>
    <path d="M3 12V4h8l10 10-8 8z" />
    <circle cx="7.5" cy="8.5" r="1.25" />
  </Icon>
);

export const Users = () => (
  <Icon>
    <circle cx="9" cy="8" r="3.25" />
    <path d="M3 19c.6-3.2 3-5 6-5s5.4 1.8 6 5" />
    <path d="M15.5 4.9a3.25 3.25 0 010 6.2M17.5 14.4c1.9.6 3.1 2.2 3.5 4.6" />
  </Icon>
);

export const LogOut = () => (
  <Icon>
    <path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11" />
  </Icon>
);

export const Gauge = () => (
  <Icon>
    <path d="M4 18a8 8 0 1 1 16 0" />
    <path d="M12 18l4-6" />
  </Icon>
);

export const History = () => (
  <Icon>
    <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
    <path d="M3 3v5h5" />
    <path d="M12 7v5l3 2" />
  </Icon>
);

export const TrendUp = () => (
  <Icon>
    <path d="M3 17l6-6 4 4 8-8" />
    <path d="M15 7h6v6" />
  </Icon>
);

/** A suitcase, for trips. */
export const Suitcase = () => (
  <Icon>
    <rect x="3.5" y="7" width="17" height="13" rx="2" />
    <path d="M9 7V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v2" />
    <path d="M8 7v13M16 7v13" />
  </Icon>
);
