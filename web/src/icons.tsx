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

export const LogOut = () => (
  <Icon>
    <path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11" />
  </Icon>
);
